/*
 * guestshim.c — LD_PRELOAD shim that gives the proot'd Alpine guest a working
 * name-resolution layer.
 *
 * Why it exists: inside proot on this device, musl's own resolver path fails
 * for domain names (EACCES "Permission denied" via getaddrinfo), while raw-IP
 * connects work fine. busybox survives because it ships its own resolver;
 * dynamically linked musl tools (apk, node, python, git, ...) all die at name
 * resolution. This shim replaces the resolver entry points with a
 * self-contained implementation that:
 *   1. handles numeric addresses directly,
 *   2. consults /etc/hosts,
 *   3. falls back to a minimal UDP DNS A query via sendto().
 * It covers both modern getaddrinfo users and legacy res_query/res_search
 * users, and avoids the musl code path (netlink interface enumeration used by
 * AI_ADDRCONFIG) that trips under proot/Android.
 *
 * Every resolver call is appended to /tmp/shim.log inside the guest so
 * failures can be diagnosed on-device (the file lands in rootfs/tmp/).
 *
 * Build freestanding (-nostdlib) so the .so carries no DT_NEEDED on Bionic
 * libc; the musl dynamic linker resolves malloc/open/socket/... it references
 * against the guest's own libc at load time.
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

/* libc symbols resolved against the guest's musl at load time.
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
extern int   close(int);
extern ssize_t_ sendto(int, const void *, sz_t, int, const struct sockaddr *, socklen_t_);
extern ssize_t_ recvfrom(int, void *, sz_t, int, struct sockaddr *, socklen_t_ *);
extern int  *__errno_location(void);
extern int   getpid(void);
extern void *dlsym(void *, const char *);

/* Forward declarations of this shim's own interposers (defined below). */
int      open(const char *, int, ...);
int      openat(int, const char *, int, ...);
ssize_t_ write(int, const void *, sz_t);
ssize_t_ read(int, void *, sz_t);
ssize_t_ send(int, const void *, sz_t, int);
ssize_t_ recv(int, void *, sz_t, int);
int      socket(int, int, int);
int      connect(int, const struct sockaddr *, socklen_t_);
int      bind(int, const struct sockaddr *, socklen_t_);
int      getsockopt(int, int, int, void *, socklen_t_ *);
int      setsockopt(int, int, int, const void *, socklen_t_);

#define RTLD_NEXT_ ((void *)-1)

#define O_RDONLY 0
#define O_WRONLY 1
#define O_CREAT  0x40
#define O_APPEND 0x400

/* ---------------------------------------------------------------- logging */

#define SHIM_LOG_PATH "/tmp/shim.log"

static int shim_logging_active = 0;

static void log_raw(const char *s, sz_t n) {
    if (shim_logging_active) return;
    shim_logging_active = 1;
    int fd = open(SHIM_LOG_PATH, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd >= 0) {
        write(fd, s, n);
        close(fd);
    }
    shim_logging_active = 0;
}

static void log_str(const char *s) {
    if (s) log_raw(s, strlen(s));
}

static void log_int(const char *key, long v) {
    char b[32];
    int i = 0, neg = 0;
    unsigned long u;
    if (key) { log_str(key); log_raw("=", 1); }
    if (v < 0) { neg = 1; u = (unsigned long)(-v); } else u = (unsigned long)v;
    char tmp[24];
    int j = 0;
    if (u == 0) tmp[j++] = '0';
    while (u > 0) { tmp[j++] = (char)('0' + (u % 10)); u /= 10; }
    if (neg) b[i++] = '-';
    while (j > 0) b[i++] = tmp[--j];
    b[i++] = '\n';
    log_raw(b, (sz_t)i);
}

static void log_kv(const char *key, const char *val) {
    if (key) { log_str(key); log_raw("=", 1); }
    log_str(val ? val : "(null)");
    log_raw("\n", 1);
}

static void log_errno(const char *key) {
    int e = *__errno_location();
    log_int(key, e);
}

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

/* Read up to `max` nameserver IPs (dotted quad) from /etc/resolv.conf. */
static int resolv_nameservers(u32 *out, int max) {
    int fd = open("/etc/resolv.conf", O_RDONLY);
    if (fd < 0) {
        log_kv("resolv open", "FAILED");
        log_errno("resolv errno");
        /* last-resort public resolvers */
        if (max >= 2) {
            parse_ipv4("8.8.8.8", &out[0]);
            parse_ipv4("1.1.1.1", &out[1]);
            return 2;
        }
        return 0;
    }
    char buf[512];
    ssize_t_ total = 0, r;
    while (total < (ssize_t_)sizeof(buf) - 1) {
        r = read(fd, buf + total, sizeof(buf) - 1 - (sz_t)total);
        if (r <= 0) break;
        total += r;
    }
    close(fd);
    buf[total] = 0;
    int n = 0;
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
    log_int("dns ns_count", nns);

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
        if (fd < 0) {
            log_errno("dns socket errno");
            continue;
        }
        struct timeval tv;
        tv.tv_sec = 4; tv.tv_usec = 0;
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

        struct sockaddr_in dst;
        memset(&dst, 0, sizeof(dst));
        dst.sin_family = AF_INET;
        dst.sin_port = htons_(53);
        dst.sin_addr = ns[i];

        ssize_t_ sn = sendto(fd, q, (sz_t)off, 0, (struct sockaddr *)&dst, sizeof(dst));
        if (sn < 0) {
            log_errno("dns sendto errno");
            close(fd);
            continue;
        }
        log_int("dns sent_to_ns", i);

        u8 resp[1024];
        ssize_t_ rn = recvfrom(fd, resp, sizeof(resp), 0, 0, 0);
        close(fd);
        log_int("dns recv bytes", (long)rn);
        if (rn < 0) { log_errno("dns recv errno"); continue; }
        if (rn < 12) continue;

        int rcode = resp[3] & 0x0f;
        int qd = (resp[4] << 8) | resp[5];
        int an = (resp[6] << 8) | resp[7];
        log_int("dns rcode", rcode);
        log_int("dns answers", an);
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

__attribute__((constructor))
static void shim_init(void) {
    log_kv("shim", "loaded");
    log_int("shim pid", (long)getpid());
}

/* ------------------------------------------- syscall interposers (diag) ---
   Log connect()/socket()/open()/openat() outcomes to pinpoint where apk's
   fetch dies after DNS starts working. Passthrough via dlsym(RTLD_NEXT). */

typedef int (*connect_fn)(int, const struct sockaddr *, socklen_t_);
typedef int (*socket_fn)(int, int, int);
typedef int (*openat_fn)(int, const char *, int, ...);
typedef int (*open_fn)(const char *, int, ...);

static void log_ipv4_sa(const struct sockaddr *sa) {
    const struct sockaddr_in *sin = (const struct sockaddr_in *)sa;
    char b[48];
    u32 a = sin->sin_addr;
    u16 port = ntohs_(sin->sin_port);
    int i = 0;
    b[i++] = 'a'; b[i++] = '=';
    /* address octets */
    for (int k = 0; k < 4; k++) {
        u32 oct = (a >> (8 * k)) & 0xff;
        char tmp[4]; int j = 0;
        if (oct == 0) tmp[j++] = '0';
        while (oct > 0) { tmp[j++] = (char)('0' + (oct % 10)); oct /= 10; }
        while (j > 0) b[i++] = tmp[--j];
        if (k < 3) b[i++] = '.';
    }
    b[i++] = ' '; b[i++] = 'p'; b[i++] = '=';
    {
        char tmp[6]; int j = 0;
        if (port == 0) tmp[j++] = '0';
        while (port > 0) { tmp[j++] = (char)('0' + (port % 10)); port /= 10; }
        while (j > 0) b[i++] = tmp[--j];
    }
    b[i++] = '\n';
    log_raw(b, (sz_t)i);
}

int connect(int fd, const struct sockaddr *sa, socklen_t_ len) {
    static connect_fn real;
    if (!real) real = (connect_fn)dlsym(RTLD_NEXT_, "connect");
    int r = real(fd, sa, len);
    int e = *__errno_location();
    if (sa && sa->sa_family == AF_INET) {
        log_kv("connect", r < 0 ? "FAIL" : "ok");
        log_ipv4_sa(sa);
    } else {
        log_kv("connect", r < 0 ? "FAIL-noninet" : "ok-noninet");
    }
    if (r < 0) log_int("connect errno", e);
    *__errno_location() = e;
    return r;
}

int socket(int domain, int type, int protocol) {
    static socket_fn real;
    if (!real) real = (socket_fn)dlsym(RTLD_NEXT_, "socket");
    int r = real(domain, type, protocol);
    int e = *__errno_location();
    if (r < 0) {
        log_int("socket FAIL domain", domain);
        log_int("socket type", type);
        log_int("socket errno", e);
    }
    *__errno_location() = e;
    return r;
}

int openat(int dirfd, const char *path, int flags, ...) {
    static openat_fn real;
    if (!real) real = (openat_fn)dlsym(RTLD_NEXT_, "openat");
    int mode = 0666;
    if (flags & O_CREAT) {
        __builtin_va_list ap;
        __builtin_va_start(ap, flags);
        mode = __builtin_va_arg(ap, int);
        __builtin_va_end(ap);
    }
    int r = real(dirfd, path, flags, mode);
    int e = *__errno_location();
    if (r < 0 && path) {
        log_kv("openat FAIL", path);
        log_int("openat errno", e);
    }
    *__errno_location() = e;
    return r;
}

int open(const char *path, int flags, ...) {
    static open_fn real_open;
    if (!real_open) real_open = (open_fn)dlsym(RTLD_NEXT_, "open");
    int mode = 0666;
    if (flags & O_CREAT) {
        __builtin_va_list ap;
        __builtin_va_start(ap, flags);
        mode = __builtin_va_arg(ap, int);
        __builtin_va_end(ap);
    }
    int r = real_open(path, flags, mode);
    int e = *__errno_location();
    if (r < 0 && path) {
        log_kv("open FAIL", path);
        log_int("open errno", e);
    }
    *__errno_location() = e;
    return r;
}

/* ---- IO tracers: find which op returns EACCES during the TLS fetch ---- */

static void log_io_ok(const char *op, int fd, long n);

typedef ssize_t_ (*io_fd_fn)(int, const void *, sz_t);
typedef ssize_t_ (*io_fd_fl_fn)(int, const void *, sz_t, int);
typedef ssize_t_ (*recv_fn)(int, void *, sz_t, int);
typedef int (*getsockopt_fn)(int, int, int, void *, socklen_t_ *);
typedef int (*setsockopt_fn)(int, int, int, const void *, socklen_t_);
typedef int (*bind_fn)(int, const struct sockaddr *, socklen_t_);

#define EAGAIN_ 11

ssize_t_ write(int fd, const void *buf, sz_t n) {
    static io_fd_fn real;
    if (!real) real = (io_fd_fn)dlsym(RTLD_NEXT_, "write");
    ssize_t_ r = real(fd, buf, n);
    int e = *__errno_location();
    if (r < 0 && e != EAGAIN_) { log_int("write FAIL fd", fd); log_int("write errno", e); }
    else if (r > 0) log_io_ok("write", fd, (long)r);
    *__errno_location() = e;
    return r;
}

ssize_t_ send(int fd, const void *buf, sz_t n, int flags) {
    static io_fd_fl_fn real;
    if (!real) real = (io_fd_fl_fn)dlsym(RTLD_NEXT_, "send");
    ssize_t_ r = real(fd, buf, n, flags);
    int e = *__errno_location();
    if (r < 0 && e != EAGAIN_) { log_int("send FAIL fd", fd); log_int("send errno", e); }
    else if (r > 0) log_io_ok("send", fd, (long)r);
    *__errno_location() = e;
    return r;
}

ssize_t_ read(int fd, void *buf, sz_t n) {
    static io_fd_fn real;
    if (!real) real = (io_fd_fn)dlsym(RTLD_NEXT_, "read");
    ssize_t_ r = real(fd, (const void *)buf, n);
    int e = *__errno_location();
    if (r < 0 && e != EAGAIN_) { log_int("read FAIL fd", fd); log_int("read errno", e); }
    else if (r > 0) log_io_ok("read", fd, (long)r);
    *__errno_location() = e;
    return r;
}

ssize_t_ recv(int fd, void *buf, sz_t n, int flags) {
    static recv_fn real;
    if (!real) real = (recv_fn)dlsym(RTLD_NEXT_, "recv");
    ssize_t_ r = real(fd, buf, n, flags);
    int e = *__errno_location();
    if (r < 0 && e != EAGAIN_) { log_int("recv FAIL fd", fd); log_int("recv errno", e); }
    else if (r > 0) log_io_ok("recv", fd, (long)r);
    *__errno_location() = e;
    return r;
}

int getsockopt(int fd, int level, int optname, void *optval, socklen_t_ *optlen) {
    static getsockopt_fn real;
    if (!real) real = (getsockopt_fn)dlsym(RTLD_NEXT_, "getsockopt");
    int r = real(fd, level, optname, optval, optlen);
    int e = *__errno_location();
    if (r == 0 && level == SOL_SOCKET && optname == 4 /* SO_ERROR */ && optval) {
        log_int("getsockopt SO_ERROR", (long)*(int *)optval);
    }
    if (r < 0) { log_int("getsockopt FAIL opt", optname); log_int("getsockopt errno", e); }
    *__errno_location() = e;
    return r;
}

int setsockopt(int fd, int level, int optname, const void *optval, socklen_t_ optlen) {
    static setsockopt_fn real;
    if (!real) real = (setsockopt_fn)dlsym(RTLD_NEXT_, "setsockopt");
    int r = real(fd, level, optname, optval, optlen);
    int e = *__errno_location();
    if (r < 0) {
        log_int("setsockopt FAIL level", level);
        log_int("setsockopt opt", optname);
        log_int("setsockopt errno", e);
    }
    *__errno_location() = e;
    return r;
}

int bind(int fd, const struct sockaddr *sa, socklen_t_ len) {
    static bind_fn real;
    if (!real) real = (bind_fn)dlsym(RTLD_NEXT_, "bind");
    int r = real(fd, sa, len);
    int e = *__errno_location();
    if (r < 0) { log_kv("bind", "FAIL"); log_int("bind errno", e); }
    *__errno_location() = e;
    return r;
}

/* ---- round 2 tracers: msg/vectored IO, fs mutations, mmap, getrandom ---- */

struct iovec { void *iov_base; sz_t iov_len; };
struct msghdr {
    void *msg_name; socklen_t_ msg_namelen;
    struct iovec *msg_iov; int msg_iovlen;
    void *msg_control; sz_t msg_controllen;
    int msg_flags;
};

typedef ssize_t_ (*sendmsg_fn)(int, const struct msghdr *, int);
typedef ssize_t_ (*recvmsg_fn)(int, struct msghdr *, int);
typedef ssize_t_ (*iovec_fn)(int, const struct iovec *, int);
typedef ssize_t_ (*iovec_r_fn)(int, const struct iovec *, int);
typedef int (*mkdir_fn)(const char *, unsigned int);
typedef int (*rename_fn)(const char *, const char *);
typedef int (*unlink_fn)(const char *);
typedef int (*ftrunc_fn)(int, long);
typedef int (*chmod_fn)(const char *, unsigned int);
typedef int (*fchmod_fn)(int, unsigned int);
typedef int (*access_fn)(const char *, int);
typedef void *(*mmap_fn)(void *, sz_t, int, int, int, long);
typedef long (*getrandom_fn)(void *, sz_t, unsigned int);

static int shim_io_events = 0;

static void log_io_ok(const char *op, int fd, long n) {
    if (shim_io_events >= 60 || fd < 3) return;
    shim_io_events++;
    log_kv(op, "ok");
    log_int("  fd", fd);
    log_int("  bytes", n);
}

ssize_t_ sendmsg(int fd, const struct msghdr *msg, int flags) {
    static sendmsg_fn real;
    if (!real) real = (sendmsg_fn)dlsym(RTLD_NEXT_, "sendmsg");
    ssize_t_ r = real(fd, msg, flags);
    int e = *__errno_location();
    if (r < 0 && e != EAGAIN_) { log_int("sendmsg FAIL fd", fd); log_int("sendmsg errno", e); }
    else if (r >= 0) log_io_ok("sendmsg", fd, (long)r);
    *__errno_location() = e;
    return r;
}

ssize_t_ recvmsg(int fd, struct msghdr *msg, int flags) {
    static recvmsg_fn real;
    if (!real) real = (recvmsg_fn)dlsym(RTLD_NEXT_, "recvmsg");
    ssize_t_ r = real(fd, msg, flags);
    int e = *__errno_location();
    if (r < 0 && e != EAGAIN_) { log_int("recvmsg FAIL fd", fd); log_int("recvmsg errno", e); }
    else if (r > 0) log_io_ok("recvmsg", fd, (long)r);
    *__errno_location() = e;
    return r;
}

ssize_t_ writev(int fd, const struct iovec *iov, int iovcnt) {
    static iovec_fn real;
    if (!real) real = (iovec_fn)dlsym(RTLD_NEXT_, "writev");
    ssize_t_ r = real(fd, iov, iovcnt);
    int e = *__errno_location();
    if (r < 0 && e != EAGAIN_) { log_int("writev FAIL fd", fd); log_int("writev errno", e); }
    else if (r >= 0) log_io_ok("writev", fd, (long)r);
    *__errno_location() = e;
    return r;
}

ssize_t_ readv(int fd, const struct iovec *iov, int iovcnt) {
    static iovec_r_fn real;
    if (!real) real = (iovec_r_fn)dlsym(RTLD_NEXT_, "readv");
    ssize_t_ r = real(fd, iov, iovcnt);
    int e = *__errno_location();
    if (r < 0 && e != EAGAIN_) { log_int("readv FAIL fd", fd); log_int("readv errno", e); }
    else if (r > 0) log_io_ok("readv", fd, (long)r);
    *__errno_location() = e;
    return r;
}

int mkdir(const char *path, unsigned int mode) {
    static mkdir_fn real;
    if (!real) real = (mkdir_fn)dlsym(RTLD_NEXT_, "mkdir");
    int r = real(path, mode);
    int e = *__errno_location();
    if (r < 0) { log_kv("mkdir FAIL", path); log_int("mkdir errno", e); }
    else log_kv("mkdir ok", path);
    *__errno_location() = e;
    return r;
}

int rename(const char *oldp, const char *newp) {
    static rename_fn real;
    if (!real) real = (rename_fn)dlsym(RTLD_NEXT_, "rename");
    int r = real(oldp, newp);
    int e = *__errno_location();
    if (r < 0) { log_kv("rename FAIL", oldp); log_int("rename errno", e); }
    *__errno_location() = e;
    return r;
}

int unlink(const char *path) {
    static unlink_fn real;
    if (!real) real = (unlink_fn)dlsym(RTLD_NEXT_, "unlink");
    int r = real(path);
    int e = *__errno_location();
    if (r < 0 && e != 2) { log_kv("unlink FAIL", path); log_int("unlink errno", e); }
    *__errno_location() = e;
    return r;
}

int ftruncate(int fd, long length) {
    static ftrunc_fn real;
    if (!real) real = (ftrunc_fn)dlsym(RTLD_NEXT_, "ftruncate");
    int r = real(fd, length);
    int e = *__errno_location();
    if (r < 0) { log_int("ftruncate FAIL fd", fd); log_int("ftruncate errno", e); }
    *__errno_location() = e;
    return r;
}

int chmod(const char *path, unsigned int mode) {
    static chmod_fn real;
    if (!real) real = (chmod_fn)dlsym(RTLD_NEXT_, "chmod");
    int r = real(path, mode);
    int e = *__errno_location();
    if (r < 0) { log_kv("chmod FAIL", path); log_int("chmod errno", e); }
    *__errno_location() = e;
    return r;
}

int fchmod(int fd, unsigned int mode) {
    static fchmod_fn real;
    if (!real) real = (fchmod_fn)dlsym(RTLD_NEXT_, "fchmod");
    int r = real(fd, mode);
    int e = *__errno_location();
    if (r < 0) { log_int("fchmod FAIL fd", fd); log_int("fchmod errno", e); }
    *__errno_location() = e;
    return r;
}

int access(const char *path, int amode) {
    static access_fn real;
    if (!real) real = (access_fn)dlsym(RTLD_NEXT_, "access");
    int r = real(path, amode);
    int e = *__errno_location();
    if (r < 0 && e == 13) { log_kv("access EACCES", path); log_int("access mode", amode); }
    *__errno_location() = e;
    return r;
}

void *mmap(void *addr, sz_t length, int prot, int flags, int fd, long offset) {
    static mmap_fn real;
    if (!real) real = (mmap_fn)dlsym(RTLD_NEXT_, "mmap");
    void *r = real(addr, length, prot, flags, fd, offset);
    int e = *__errno_location();
    if (r == (void *)-1) { log_int("mmap FAIL fd", fd); log_int("mmap errno", e); }
    *__errno_location() = e;
    return r;
}

long getrandom(void *buf, sz_t buflen, unsigned int flags) {
    static getrandom_fn real;
    if (!real) real = (getrandom_fn)dlsym(RTLD_NEXT_, "getrandom");
    long r = real(buf, buflen, flags);
    int e = *__errno_location();
    if (r < 0) { log_kv("getrandom", "FAIL"); log_int("getrandom errno", e); }
    *__errno_location() = e;
    return r;
}


int getaddrinfo(const char *host, const char *serv,
                const struct addrinfo *hint, struct addrinfo **res) {
    log_kv("gai host", host);
    log_kv("gai serv", serv);
    log_int("gai family", hint ? hint->ai_family : -1);
    log_int("gai flags", hint ? hint->ai_flags : -1);

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
        log_kv("gai result", "any-addr");
        return 0;
    }

    /* 1) numeric IPv4 */
    u32 v4;
    if (parse_ipv4(host, &v4)) {
        if (want_family == AF_INET6) return EAI_NONAME;
        struct addrinfo *ai = make_ai(AF_INET, socktype, protocol, &v4, 4, port);
        if (!ai) return EAI_MEMORY;
        *res = ai;
        log_kv("gai result", "numeric-v4");
        return 0;
    }
    /* 2) numeric IPv6 */
    u8 v6[16];
    if (parse_ipv6(host, v6)) {
        if (want_family == AF_INET) return EAI_NONAME;
        struct addrinfo *ai = make_ai(AF_INET6, socktype, protocol, v6, 16, port);
        if (!ai) return EAI_MEMORY;
        *res = ai;
        log_kv("gai result", "numeric-v6");
        return 0;
    }

    /* 3) /etc/hosts */
    char *ipstr = hosts_lookup(host);
    if (ipstr) {
        log_kv("gai hosts hit", ipstr);
        struct addrinfo *ai = 0;
        u32 a4;
        u8 a6[16];
        if (parse_ipv4(ipstr, &a4) && (want_family == 0 || want_family == AF_INET)) {
            ai = make_ai(AF_INET, socktype, protocol, &a4, 4, port);
        } else if (parse_ipv6(ipstr, a6) && (want_family == 0 || want_family == AF_INET6)) {
            ai = make_ai(AF_INET6, socktype, protocol, a6, 16, port);
        }
        free(ipstr);
        if (ai) { *res = ai; log_kv("gai result", "hosts"); return 0; }
    }

    /* 4) DNS A query (IPv4). */
    if (want_family == 0 || want_family == AF_INET) {
        u32 a4;
        if (dns_query_a(host, &a4)) {
            struct addrinfo *ai = make_ai(AF_INET, socktype, protocol, &a4, 4, port);
            if (!ai) return EAI_MEMORY;
            *res = ai;
            log_kv("gai result", "dns-ok");
            return 0;
        }
    }

    log_kv("gai result", "NONAME");
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
   Some tools (older libfetch/apk code paths) use res_query/res_search instead
   of getaddrinfo. Provide working versions backed by the same DNS client: we
   answer A queries by synthesizing a minimal DNS response packet. */

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
    log_kv("res_query name", name);
    log_int("res_query type", type);
    if (!name || !dest) return -1;
    if (class_ != 1 || type != 1) {
        log_kv("res_query", "unsupported class/type");
        return -1;
    }
    /* hosts file first */
    char *ipstr = hosts_lookup(name);
    if (ipstr) {
        u32 a4;
        int ok = parse_ipv4(ipstr, &a4);
        free(ipstr);
        if (ok) {
            int n = dns_build_response_a(name, a4, dest, destlen);
            log_int("res_query hosts bytes", n);
            return n;
        }
    }
    u32 a4;
    if (dns_query_a(name, &a4)) {
        int n = dns_build_response_a(name, a4, dest, destlen);
        log_int("res_query dns bytes", n);
        return n;
    }
    log_kv("res_query", "failed");
    return -1;
}

int res_search(const char *name, int class_, int type, unsigned char *dest, int destlen) {
    log_kv("res_search name", name);
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
    log_kv("getifaddrs", "called");
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
