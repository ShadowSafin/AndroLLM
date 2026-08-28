/*
 * guestshim.c — LD_PRELOAD shim that gives the proot'd Linux guest a
 * self-contained name-resolution layer.
 *
 * Why it exists: inside proot on Android, the stock libc resolver paths can
 * fail for domain names (observed on musl/Alpine: getaddrinfo returning
 * EACCES "Permission denied" while raw-IP connects work fine). Dynamically
 * linked tools (apt/apk, node, python, git, ...) then cannot reach any
 * hostname. This shim replaces the resolver entry points with a
 * self-contained implementation that:
 *   1. handles numeric addresses directly,
 *   2. consults /etc/hosts,
 *   3. falls back to a minimal UDP DNS A query via sendto().
 * It covers both modern getaddrinfo users and legacy res_query/res_search
 * users, and never touches netlink interface enumeration (the path that
 * trips under proot/Android).
 *
 * It also emulates link()/linkat() (as file copies): Android denies hard
 * links in app storage outright, and dpkg cannot install anything without
 * them (see the hardlink layer at the bottom).
 *
 * Build freestanding (-nostdlib) so the .so carries no DT_NEEDED on Bionic
 * libc; the guest's dynamic linker resolves malloc/open/socket/... it
 * references against the guest's own libc at load time. The same binary
 * works under musl (Alpine) and glibc (Debian) guests.
 */

typedef unsigned char      u8;
typedef unsigned short     u16;
typedef unsigned int       u32;
typedef unsigned long long u64;
typedef long               ssize_t_;
typedef int                socklen_t_;
typedef long               time_t_;

#define AF_INET   2
#define AF_INET6  10
#define SOCK_STREAM 1
#define SOCK_DGRAM  2
#define IPPROTO_TCP 6
#define IPPROTO_UDP 17
#define SOL_SOCKET 1
#define SO_RCVTIMEO 20
#define EAI_NONAME  -2
#define EAI_AGAIN   -3
#define EAI_FAIL    -4
#define EAI_FAMILY  -6
#define EAI_MEMORY  -10
#define EAI_SYSTEM  -11

struct sockaddr {
    u16 sa_family;
    char sa_data[14];
};
struct sockaddr_in {
    u16 sin_family;
    u16 sin_port;      /* network order */
    u32 sin_addr;      /* network order */
    char sin_zero[8];
};
struct sockaddr_in6 {
    u16 sin6_family;
    u16 sin6_port;     /* network order */
    u32 sin6_flowinfo;
    u8  sin6_addr[16];
    u32 sin6_scope_id;
};

struct addrinfo {
    int ai_flags;
    int ai_family;
    int ai_socktype;
    int ai_protocol;
    socklen_t_ ai_addrlen;
    struct sockaddr *ai_addr;
    char *ai_canonname;
    struct addrinfo *ai_next;
};

struct ifaddrs {
    struct ifaddrs *ifa_next;
    char *ifa_name;
    unsigned int ifa_flags;
    struct sockaddr *ifa_addr;
    struct sockaddr *ifa_netmask;
    union {
        struct sockaddr *ifu_broadaddr;
        struct sockaddr *ifu_dstaddr;
    } ifa_ifu;
    void *ifa_data;
};

struct timeval {
    time_t_ tv_sec;
    time_t_ tv_usec;
};

/* libc symbols resolved against the guest's libc at load time.
   size_t on aarch64 Linux is `unsigned long`; match the builtin signatures. */
typedef unsigned long sz_t;
extern void *malloc(sz_t);
extern void *calloc(sz_t, sz_t);
extern void  free(void *);
extern void *memset(void *, int, sz_t);
extern void *memcpy(void *, const void *, sz_t);
extern sz_t  strlen(const char *);
extern int   strcmp(const char *, const char *);
extern int   strcasecmp(const char *, const char *);
extern int   open(const char *, int, ...);
extern ssize_t_ read(int, void *, sz_t);
extern ssize_t_ write(int, const void *, sz_t);
extern int   close(int);
extern int   socket(int, int, int);
extern ssize_t_ sendto(int, const void *, sz_t, int, const struct sockaddr *, socklen_t_);
extern ssize_t_ recvfrom(int, void *, sz_t, int, struct sockaddr *, socklen_t_ *);
extern int   setsockopt(int, int, int, const void *, socklen_t_);
extern int   fstat(int, void *);
extern int  *__errno_location(void);

#define O_RDONLY 0
#define O_WRONLY 1
#define O_CREAT  0x40
#define O_TRUNC  0x200
#define AT_FDCWD_ (-100)
#define ENOSYS_ 38

/* ---------------------------------------------------------------- helpers */

static u16 htons_(u16 v) { return (u16)((v >> 8) | (v << 8)); }
static u16 ntohs_(u16 v) { return htons_(v); }

static int isdigit_(int c) { return c >= '0' && c <= '9'; }

static int hexval(int c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Parse a dotted-quad IPv4 string into a network-order u32. Returns 1 on ok. */
static int parse_ipv4(const char *s, u32 *out) {
    u8 oct[4];
    int n = 0, i = 0;
    while (s[i]) {
        int val = 0, digits = 0;
        while (isdigit_((int)s[i])) {
            val = val * 10 + (s[i] - '0');
            if (val > 255) return 0;
            i++; digits++;
        }
        if (digits == 0) return 0;
        oct[n++] = (u8)val;
        if (n == 4) break;
        if (s[i] != '.') return 0;
        i++;
    }
    if (n != 4) return 0;
    *out = ((u32)oct[0]) | ((u32)oct[1] << 8) | ((u32)oct[2] << 16) | ((u32)oct[3] << 24);
    return 1;
}

/* Parse a (possibly compressed) IPv6 string into 16 network-order bytes. */
static int parse_ipv6(const char *s, u8 *out) {
    u16 groups[8];
    int ng = 0, dbl = -1, i = 0, g;
    memset(out, 0, 16);
    if (s[0] == ':' && s[1] == ':') { dbl = 0; i = 2; if (!s[2]) { return 1; } }
    while (s[i] && ng < 8) {
        if (s[i] == ':') {
            if (dbl >= 0) return 0;
            dbl = ng;
            i++;
            if (!s[i]) break;
            continue;
        }
        u32 val = 0; int digits = 0;
        while (hexval((int)s[i]) >= 0 && digits < 4) {
            val = (val << 4) | (u32)hexval((int)s[i]);
            i++; digits++;
        }
        if (digits == 0) return 0;
        groups[ng++] = (u16)val;
        if (s[i] == ':') { i++; if (!s[i]) { if (dbl >= 0) return 0; dbl = ng; break; } }
        else if (s[i] == '.') {
            /* trailing dotted-quad */
            u32 v4;
            /* rewind to start of this group's text */
            int start = i - digits;
            if (!parse_ipv4(&s[start], &v4)) return 0;
            ng--; /* discard the partial group we just read */
            if (ng > 6) return 0;
            groups[ng++] = (u16)(v4 & 0xffff);
            groups[ng++] = (u16)(v4 >> 16);
            i = (int)strlen(s);
            break;
        }
        else if (s[i]) return 0;
    }
    if (s[i]) return 0;
    g = 0;
    if (dbl < 0) {
        if (ng != 8) return 0;
        for (int k = 0; k < 8; k++) { out[g++] = (u8)(groups[k] >> 8); out[g++] = (u8)(groups[k] & 0xff); }
    } else {
        int nbefore = dbl, nafter = ng - dbl;
        if (nafter < 0) nafter = 0;
        if (nbefore + nafter > 8) return 0;
        for (int k = 0; k < nbefore; k++) { out[g++] = (u8)(groups[k] >> 8); out[g++] = (u8)(groups[k] & 0xff); }
        int nz = 8 - nbefore - nafter;
        for (int k = 0; k < nz; k++) { out[g++] = 0; out[g++] = 0; }
        for (int k = 0; k < nafter; k++) { int idx = dbl + k; out[g++] = (u8)(groups[idx] >> 8); out[g++] = (u8)(groups[idx] & 0xff); }
    }
    return 1;
}

/* ------------------------------------------------------- /etc/hosts lookup */

/* Returns a malloc'd copy of the first IP string for `name` in /etc/hosts, or 0. */
static char *hosts_lookup(const char *name) {
    int fd = open("/etc/hosts", O_RDONLY);
    if (fd < 0) return 0;
    char buf[2048];
    ssize_t_ total = 0, r;
    while (total < (ssize_t_)sizeof(buf) - 1) {
        r = read(fd, buf + total, sizeof(buf) - 1 - (sz_t)total);
        if (r <= 0) break;
        total += r;
    }
    close(fd);
    buf[total] = 0;

    char *line = buf;
    while (line && *line) {
        char *nl = line;
        while (*nl && *nl != '\n') nl++;
        if (*nl == '\n') { *nl = 0; nl++; } else { nl = 0; }
        /* parse line: IP alias... */
        char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        if (*p && *p != '#') {
            char *ip = p;
            while (*p && *p != ' ' && *p != '\t') p++;
            if (*p) { *p = 0; p++; }
            /* scan aliases */
            int found = 0;
            char *q = p;
            while (*q) {
                while (*q == ' ' || *q == '\t') q++;
                char *alias = q;
                while (*q && *q != ' ' && *q != '\t') q++;
                char save = *q; if (*q) { *q = 0; q++; }
                if (*alias && strcasecmp(alias, name) == 0) { found = 1; }
                if (!save) break;
            }
            if (found) {
                sz_t len = strlen(ip) + 1;
                char *res = (char *)malloc(len);
                if (res) memcpy(res, ip, len);
                return res;
            }
        }
        line = nl;
    }
    return 0;
}

/* -------------------------------------------------------------- DNS query */

/* Encode a domain name into DNS wire format at dst; returns bytes written. */
static int dns_encode_name(const char *name, u8 *dst) {
    int n = 0, i = 0, lab = 0;
    dst[0] = 0;
    while (name[i]) {
        if (name[i] == '.') {
            dst[lab] = (u8)(n - lab);
            lab = n + 1;
            dst[lab] = 0;
        } else {
            dst[n + 1] = (u8)name[i];
        }
        n++;
        i++;
    }
    dst[lab] = (u8)(n - lab);
    dst[n + 1] = 0;
    return n + 2;
}

/* Read up to `max` nameserver IPs (dotted quad) from /etc/resolv.conf.
   Falls back to public resolvers when the file is missing/empty. */
static int resolv_nameservers(u32 *out, int max) {
    int n = 0;
    int fd = open("/etc/resolv.conf", O_RDONLY);
    if (fd >= 0) {
        char buf[512];
        ssize_t_ total = 0, r;
        while (total < (ssize_t_)sizeof(buf) - 1) {
            r = read(fd, buf + total, sizeof(buf) - 1 - (sz_t)total);
            if (r <= 0) break;
            total += r;
        }
        close(fd);
        buf[total] = 0;
        char *line = buf;
        while (line && *line && n < max) {
            char *nl = line;
            while (*nl && *nl != '\n') nl++;
            if (*nl == '\n') { *nl = 0; nl++; } else { nl = 0; }
            char *p = line;
            while (*p == ' ' || *p == '\t') p++;
            if (p[0]=='n' && p[1]=='a' && p[2]=='m' && p[3]=='e' && p[4]=='s' && p[5]=='e' && p[6]=='r' && p[7]=='v' && p[8]=='e' && p[9]=='r') {
                p += 10;
                while (*p == ' ' || *p == '\t') p++;
                char *ip = p;
                while (*p && *p != ' ' && *p != '\t' && *p != '\r') p++;
                *p = 0;
                u32 v4;
                if (parse_ipv4(ip, &v4)) out[n++] = v4;
            }
            line = nl;
        }
    }
    if (n == 0 && max >= 2) {
        parse_ipv4("8.8.8.8", &out[0]);
        parse_ipv4("1.1.1.1", &out[1]);
        return 2;
    }
    return n;
}

/* Skip a DNS name in the packet (handles compression pointers). */
static int dns_skip_name(const u8 *pkt, int len, int off) {
    int jumps = 0;
    while (off < len) {
        u8 l = pkt[off];
        if (l == 0) { return off + 1; }
        if ((l & 0xC0) == 0xC0) { return off + 2; }
        off += 1 + l;
        if (++jumps > 64) return -1;
    }
    return -1;
}

/*
 * Query the resolver for an A record of `name`. On success fills out_ip
 * (network order) and returns 1. Tries each configured nameserver in turn.
 */
static int dns_query_a(const char *name, u32 *out_ip) {
    u32 ns[3];
    int nns = resolv_nameservers(ns, 3);

    u8 q[512];
    memset(q, 0, sizeof(q));
    u16 id = 0x1234;
    q[0] = (u8)(id >> 8); q[1] = (u8)(id & 0xff);
    q[2] = 0x01; q[3] = 0x00;           /* RD */
    q[4] = 0x00; q[5] = 0x01;           /* QDCOUNT=1 */
    int off = 12;
    off += dns_encode_name(name, q + off);
    q[off++] = 0x00; q[off++] = 0x01;   /* QTYPE=A */
    q[off++] = 0x00; q[off++] = 0x01;   /* QCLASS=IN */

    for (int i = 0; i < nns; i++) {
        int fd = socket(AF_INET, SOCK_DGRAM, 0);
        if (fd < 0) continue;
        struct timeval tv;
        tv.tv_sec = 4; tv.tv_usec = 0;
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

        struct sockaddr_in dst;
        memset(&dst, 0, sizeof(dst));
        dst.sin_family = AF_INET;
        dst.sin_port = htons_(53);
        dst.sin_addr = ns[i];

        if (sendto(fd, q, (sz_t)off, 0, (struct sockaddr *)&dst, sizeof(dst)) < 0) {
            close(fd);
            continue;
        }

        u8 resp[1024];
        ssize_t_ rn = recvfrom(fd, resp, sizeof(resp), 0, 0, 0);
        close(fd);
        if (rn < 12) continue;

        int rcode = resp[3] & 0x0f;
        int qd = (resp[4] << 8) | resp[5];
        int an = (resp[6] << 8) | resp[7];
        if (rcode != 0 || an == 0) continue;

        int p = 12;
        /* skip questions */
        for (int k = 0; k < qd; k++) {
            p = dns_skip_name(resp, (int)rn, p);
            if (p < 0) break;
            p += 4;
        }
        if (p < 0) continue;
        /* walk answers */
        for (int k = 0; k < an && p < (int)rn; k++) {
            p = dns_skip_name(resp, (int)rn, p);
            if (p < 0) break;
            if (p + 10 > (int)rn) break;
            u16 type = (u16)((resp[p] << 8) | resp[p + 1]);
            u16 rdlen = (u16)((resp[p + 8] << 8) | resp[p + 9]);
            p += 10;
            if (type == 1 && rdlen == 4 && p + 4 <= (int)rn) {
                *out_ip = ((u32)resp[p]) | ((u32)resp[p+1] << 8) | ((u32)resp[p+2] << 16) | ((u32)resp[p+3] << 24);
                return 1;
            }
            p += rdlen;
        }
    }
    return 0;
}

/* ---------------------------------------------------------- addrinfo build */

static struct addrinfo *make_ai(int family, int socktype, int protocol,
                                const void *addr, int addrlen, u16 port) {
    struct addrinfo *ai = (struct addrinfo *)malloc(sizeof(struct addrinfo));
    if (!ai) return 0;
    memset(ai, 0, sizeof(*ai));
    struct sockaddr *sa;
    if (family == AF_INET) {
        struct sockaddr_in *sin = (struct sockaddr_in *)malloc(sizeof(*sin));
        if (!sin) { free(ai); return 0; }
        memset(sin, 0, sizeof(*sin));
        sin->sin_family = AF_INET;
        sin->sin_port = port;
        memcpy(&sin->sin_addr, addr, 4);
        sa = (struct sockaddr *)sin;
        ai->ai_addrlen = sizeof(*sin);
    } else {
        struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)malloc(sizeof(*sin6));
        if (!sin6) { free(ai); return 0; }
        memset(sin6, 0, sizeof(*sin6));
        sin6->sin6_family = AF_INET6;
        sin6->sin6_port = port;
        memcpy(&sin6->sin6_addr, addr, 16);
        sa = (struct sockaddr *)sin6;
        ai->ai_addrlen = sizeof(*sin6);
    }
    ai->ai_family = family;
    ai->ai_socktype = socktype;
    ai->ai_protocol = protocol;
    ai->ai_addr = sa;
    ai->ai_next = 0;
    ai->ai_canonname = 0;
    return ai;
}

/* Parse service to port (network order). Handles numeric and a few common names. */
static u16 service_port(const char *serv, int socktype) {
    if (!serv || !*serv) return 0;
    int allnum = 1, val = 0;
    for (int i = 0; serv[i]; i++) {
        if (isdigit_((int)serv[i])) val = val * 10 + (serv[i] - '0');
        else { allnum = 0; break; }
    }
    if (allnum && val <= 65535) return htons_((u16)val);
    if (strcasecmp(serv, "http") == 0)  return htons_(80);
    if (strcasecmp(serv, "https") == 0) return htons_(443);
    if (strcasecmp(serv, "ftp") == 0)   return htons_(21);
    if (strcasecmp(serv, "ssh") == 0)   return htons_(22);
    if (strcasecmp(serv, "domain") == 0)return htons_(53);
    (void)socktype;
    return 0;
}

/* ------------------------------------------------------------------ public */

int getaddrinfo(const char *host, const char *serv,
                const struct addrinfo *hint, struct addrinfo **res) {
    if (!host && !serv) return EAI_NONAME;
    if (res) *res = 0;
    if (!res) return EAI_SYSTEM;

    int want_family = hint ? hint->ai_family : 0;
    int socktype = hint ? hint->ai_socktype : 0;
    int protocol = hint ? hint->ai_protocol : 0;
    u16 port = service_port(serv, socktype);

    /* If only a service is requested, bind to any/loopback. */
    if (!host || !*host) {
        int fam = (want_family == AF_INET6) ? AF_INET6 : AF_INET;
        u8 zero4[4] = {0,0,0,0};
        u8 zero16[16] = {0};
        struct addrinfo *ai;
        if (fam == AF_INET) ai = make_ai(AF_INET, socktype, protocol, zero4, 4, port);
        else ai = make_ai(AF_INET6, socktype, protocol, zero16, 16, port);
        if (!ai) return EAI_MEMORY;
        *res = ai;
        return 0;
    }

    /* 1) numeric IPv4 */
    u32 v4;
    if (parse_ipv4(host, &v4)) {
        if (want_family == AF_INET6) return EAI_NONAME;
        struct addrinfo *ai = make_ai(AF_INET, socktype, protocol, &v4, 4, port);
        if (!ai) return EAI_MEMORY;
        *res = ai;
        return 0;
    }
    /* 2) numeric IPv6 */
    u8 v6[16];
    if (parse_ipv6(host, v6)) {
        if (want_family == AF_INET) return EAI_NONAME;
        struct addrinfo *ai = make_ai(AF_INET6, socktype, protocol, v6, 16, port);
        if (!ai) return EAI_MEMORY;
        *res = ai;
        return 0;
    }

    /* 3) /etc/hosts */
    char *ipstr = hosts_lookup(host);
    if (ipstr) {
        struct addrinfo *ai = 0;
        u32 a4;
        u8 a6[16];
        if (parse_ipv4(ipstr, &a4) && (want_family == 0 || want_family == AF_INET)) {
            ai = make_ai(AF_INET, socktype, protocol, &a4, 4, port);
        } else if (parse_ipv6(ipstr, a6) && (want_family == 0 || want_family == AF_INET6)) {
            ai = make_ai(AF_INET6, socktype, protocol, a6, 16, port);
        }
        free(ipstr);
        if (ai) { *res = ai; return 0; }
    }

    /* 4) DNS A query (IPv4). */
    if (want_family == 0 || want_family == AF_INET) {
        u32 a4;
        if (dns_query_a(host, &a4)) {
            struct addrinfo *ai = make_ai(AF_INET, socktype, protocol, &a4, 4, port);
            if (!ai) return EAI_MEMORY;
            *res = ai;
            return 0;
        }
    }

    return EAI_NONAME;
}

void freeaddrinfo(struct addrinfo *res) {
    while (res) {
        struct addrinfo *next = res->ai_next;
        if (res->ai_addr) free(res->ai_addr);
        if (res->ai_canonname) free(res->ai_canonname);
        free(res);
        res = next;
    }
}

const char *gai_strerror(int code) {
    switch (code) {
        case 0: return "Success";
        case EAI_NONAME: return "Name does not resolve";
        case EAI_AGAIN: return "Try again";
        case EAI_FAIL: return "Non-recoverable error";
        case EAI_FAMILY: return "Unrecognized address family";
        case EAI_MEMORY: return "Out of memory";
        case EAI_SYSTEM: return "System error";
        default: return "Unknown error";
    }
}

/* ------------------------------------------------ legacy resolver API -----
   Some tools use res_query/res_search instead of getaddrinfo. Provide working
   versions backed by the same DNS client: we answer A queries by synthesizing
   a minimal DNS response packet. */

static int dns_build_response_a(const char *name, u32 ip_net, u8 *dest, int destlen) {
    u8 tmp[512];
    int n = 0;
    tmp[n++] = 0x12; tmp[n++] = 0x34;     /* id (matches our query) */
    tmp[n++] = 0x81; tmp[n++] = 0x80;     /* QR|RD|RA */
    tmp[n++] = 0x00; tmp[n++] = 0x01;     /* QDCOUNT */
    tmp[n++] = 0x00; tmp[n++] = 0x01;     /* ANCOUNT */
    tmp[n++] = 0x00; tmp[n++] = 0x00;     /* NSCOUNT */
    tmp[n++] = 0x00; tmp[n++] = 0x00;     /* ARCOUNT */
    int qstart = n;
    n += dns_encode_name(name, tmp + n);
    tmp[n++] = 0x00; tmp[n++] = 0x01;     /* QTYPE A */
    tmp[n++] = 0x00; tmp[n++] = 0x01;     /* QCLASS IN */
    /* answer: name as compression pointer to the question name */
    tmp[n++] = 0xC0; tmp[n++] = (u8)qstart;
    tmp[n++] = 0x00; tmp[n++] = 0x01;     /* TYPE A */
    tmp[n++] = 0x00; tmp[n++] = 0x01;     /* CLASS IN */
    tmp[n++] = 0x00; tmp[n++] = 0x00; tmp[n++] = 0x01; tmp[n++] = 0x2C; /* TTL 300 */
    tmp[n++] = 0x00; tmp[n++] = 0x04;     /* RDLENGTH */
    tmp[n++] = (u8)(ip_net & 0xff);
    tmp[n++] = (u8)((ip_net >> 8) & 0xff);
    tmp[n++] = (u8)((ip_net >> 16) & 0xff);
    tmp[n++] = (u8)((ip_net >> 24) & 0xff);
    if (n > destlen) return -1;
    memcpy(dest, tmp, (sz_t)n);
    return n;
}

int res_query(const char *name, int class_, int type, unsigned char *dest, int destlen) {
    if (!name || !dest) return -1;
    if (class_ != 1 || type != 1) return -1;
    /* hosts file first */
    char *ipstr = hosts_lookup(name);
    if (ipstr) {
        u32 a4;
        int ok = parse_ipv4(ipstr, &a4);
        free(ipstr);
        if (ok) return dns_build_response_a(name, a4, dest, destlen);
    }
    u32 a4;
    if (dns_query_a(name, &a4)) return dns_build_response_a(name, a4, dest, destlen);
    return -1;
}

int res_search(const char *name, int class_, int type, unsigned char *dest, int destlen) {
    return res_query(name, class_, type, dest, destlen);
}

/* getifaddrs: return a small static list so AI_ADDRCONFIG-style interface
   enumeration never touches netlink (which is what EACCES'd under proot). */
static struct sockaddr_in shim_wlan_addr = { AF_INET, 0, 0x8500A8C0u, {0} }; /* 192.168.0.133 */
static struct sockaddr_in shim_wlan_mask = { AF_INET, 0, 0x00FFFFFFu, {0} };
static struct sockaddr_in shim_lo_addr   = { AF_INET, 0, 0x0100007Fu, {0} }; /* 127.0.0.1 */
static struct sockaddr_in shim_lo_mask   = { AF_INET, 0, 0x000000FFu, {0} };
static char shim_wlan_name[] = "wlan0";
static char shim_lo_name[]   = "lo";
static struct ifaddrs shim_ifs[2];
static int shim_ifs_init = 0;

int getifaddrs(struct ifaddrs **ifap) {
    if (!ifap) return -1;
    if (!shim_ifs_init) {
        shim_ifs[0].ifa_next = &shim_ifs[1];
        shim_ifs[0].ifa_name = shim_wlan_name;
        shim_ifs[0].ifa_flags = 0x1003; /* UP|BROADCAST|RUNNING */
        shim_ifs[0].ifa_addr = (struct sockaddr *)&shim_wlan_addr;
        shim_ifs[0].ifa_netmask = (struct sockaddr *)&shim_wlan_mask;
        shim_ifs[0].ifa_ifu.ifu_broadaddr = (struct sockaddr *)&shim_wlan_addr;
        shim_ifs[0].ifa_data = 0;
        shim_ifs[1].ifa_next = 0;
        shim_ifs[1].ifa_name = shim_lo_name;
        shim_ifs[1].ifa_flags = 0x49; /* UP|LOOPBACK|RUNNING */
        shim_ifs[1].ifa_addr = (struct sockaddr *)&shim_lo_addr;
        shim_ifs[1].ifa_netmask = (struct sockaddr *)&shim_lo_mask;
        shim_ifs[1].ifa_ifu.ifu_broadaddr = 0;
        shim_ifs[1].ifa_data = 0;
        shim_ifs_init = 1;
    }
    *ifap = &shim_ifs[0];
    return 0;
}

void freeifaddrs(struct ifaddrs *ifa) { (void)ifa; }

/* ------------------------------------------------------- hardlink layer ---
   Android rejects link()/linkat() in app storage with EACCES (verified
   natively, without proot, on Android 16 / OPPO). dpkg needs hardlinks for
   its status-file backup (`/var/lib/dpkg/status-old`) and package archives
   can contain hardlinks too, so without this every `apt-get install` dies:
     "dpkg: error: error creating new backup file '/var/lib/dpkg/status-old':
      Permission denied"
   We emulate a hardlink as a byte-for-byte copy (mode preserved). That is
   semantically close enough for dpkg/tar/git use on a single-user rootfs:
   nothing in the guest relies on link()d files sharing an inode afterwards. */

/* aarch64 struct stat prefix: st_dev(u64), st_ino(u64), st_mode(u32).
   Oversized tail keeps the kernel write in-bounds. */
struct stat_mini {
    u64 st_dev;
    u64 st_ino;
    u32 st_mode;
    char pad[116];
};

static int copy_as_link(const char *src, const char *dst) {
    int in = open(src, O_RDONLY);
    if (in < 0) return -1;
    struct stat_mini st;
    memset(&st, 0, sizeof(st));
    unsigned int mode = 0644;
    if (fstat(in, &st) == 0 && (st.st_mode & 0170000) == 0100000) {
        mode = st.st_mode & 07777;
    }
    int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC, mode);
    if (out < 0) { close(in); return -1; }
    char buf[8192];
    ssize_t_ r;
    int failed = 0;
    while ((r = read(in, buf, sizeof(buf))) > 0) {
        ssize_t_ w = 0;
        while (w < r) {
            ssize_t_ k = write(out, buf + w, (sz_t)(r - w));
            if (k <= 0) { failed = 1; break; }
            w += k;
        }
        if (failed) break;
    }
    if (r < 0) failed = 1;
    if (close(in) != 0) failed = 1;
    if (close(out) != 0) failed = 1;
    return failed ? -1 : 0;
}

/* Resolve a (dirfd, path) pair to an absolute-ish path we can open. */
static int resolve_at_path(int dirfd, const char *path, char *buf, sz_t buflen) {
    if (path[0] == '/' || dirfd == AT_FDCWD_) {
        sz_t l = strlen(path);
        if (l + 1 > buflen) return -1;
        memcpy(buf, path, l + 1);
        return 0;
    }
    /* /proc/self/fd/<dirfd>/<path> — /proc is bound into the guest. */
    char fdnum[16];
    int n = 0;
    {
        int v = dirfd;
        char tmp[12];
        int j = 0;
        if (v == 0) tmp[j++] = '0';
        while (v > 0) { tmp[j++] = (char)('0' + (v % 10)); v /= 10; }
        fdnum[n++] = '/';
        while (j > 0) fdnum[n++] = tmp[--j];
    }
    const char *pre = "/proc/self/fd";
    sz_t lpre = strlen(pre);
    sz_t lfd = (sz_t)n;
    sz_t lpath = strlen(path);
    if (lpre + lfd + 1 + lpath + 1 > buflen) return -1;
    memcpy(buf, pre, lpre);
    memcpy(buf + lpre, fdnum, lfd);
    buf[lpre + lfd] = '/';
    memcpy(buf + lpre + lfd + 1, path, lpath + 1);
    return 0;
}

int link(const char *oldpath, const char *newpath) {
    return copy_as_link(oldpath, newpath);
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    if (!oldpath || !newpath) {
        *__errno_location() = ENOSYS_;
        return -1;
    }
    if (oldpath[0] == '\0') {
        /* AT_EMPTY_PATH form: cannot emulate portably. */
        *__errno_location() = ENOSYS_;
        return -1;
    }
    (void)flags;
    char src[1056], dst[1056];
    if (resolve_at_path(olddirfd, oldpath, src, sizeof(src)) < 0 ||
        resolve_at_path(newdirfd, newpath, dst, sizeof(dst)) < 0) {
        *__errno_location() = ENOSYS_;
        return -1;
    }
    return copy_as_link(src, dst);
}
