"use client";

import { useEffect, useRef, useState } from "react";

// ── Monochrome aurora — ink wash / magnetic field ───────────────────────────
// Fragment-shader field that sits fixed behind the page. Scroll drives
// vertical drift + streak contrast, pointer nudges the warp. Fully
// monochrome: whites / grays over black, no color, no branding clash.
// Respects prefers-reduced-motion and coarse pointer.

const VS_300 = `#version 300 es
precision highp float;
in vec2 aPos;
void main(){ gl_Position = vec4(aPos,0.,1.); }
`;

const FS_300 = `#version 300 es
precision highp float;
uniform float uTime;
uniform vec2  uRes;
uniform float uScroll;   // 0..1
uniform float uVel;      // -1..1 scroll velocity
uniform vec2  uPointer;  // 0..1 pointer
uniform float uOpacity;
out vec4 fragColor;

// hash / value noise / fbm — cheap, tile-free
float hash(vec2 p){ p = fract(p*vec2(123.34,345.45)); p+=dot(p,p+33.33); return fract(p.x*p.y); }
float noise(vec2 p){
  vec2 i=floor(p), f=fract(p);
  float a=hash(i), b=hash(i+vec2(1.,0.)), c=hash(i+vec2(0.,1.)), d=hash(i+vec2(1.,1.));
  vec2 u=f*f*(3.-2.*f);
  return mix(mix(a,b,u.x), mix(c,d,u.x), u.y);
}
float fbm(vec2 p){
  float v=0., a=0.5;
  for(int i=0;i<4;i++){ v+=a*noise(p); p*=2.07; a*=0.5; }
  return v;
}

void main(){
  vec2 frag = gl_FragCoord.xy;
  // uv centered, aspect-correct, zoom tuned to fill
  vec2 uv = (frag - 0.5*uRes) / uRes.y;
  uv *= 1.05;

  // pointer warp — subtle magnetic pull
  vec2 pm = uPointer*2.-1.;
  pm.x *= uRes.x/uRes.y;
  float pd = length(uv - pm*0.32);
  float pull = exp(-pd*1.4) * 0.22;
  uv -= normalize(uv - pm*0.32 + 0.001) * pull;

  // scroll drives vertical drift
  float drift = uScroll * 0.9;
  uv.y += drift * 0.55;
  uv.x += sin(uv.y*0.9 + uTime*0.06) * 0.04 * (1.+ abs(uVel)*0.8);

  float t = uTime * 0.055;

  // domain warp — slow ink clouds
  vec2 q = vec2(
    fbm(uv*0.95 + vec2(0.0, t*0.9)),
    fbm(uv*0.95 + vec2(1.7, 2.3) + t*0.7)
  );
  vec2 r = vec2(
    fbm(uv*1.15 + 3.5*q + vec2(1.3, 9.2) + t*0.55),
    fbm(uv*1.15 + 3.5*q + vec2(8.3, 2.8) + t*0.48)
  );

  vec2 warpUV = uv*1.35 + 4.0*r + vec2(0., t*0.08);
  // horizontal streak bias — aurora ribbons
  float s = fbm(warpUV);
  // thread-like streaks via smoothstep shaping
  float streaks = smoothstep(0.46, 0.72, s) * smoothstep(0.88, 0.62, s);
  streaks = pow(streaks, 0.85);
  // soft clouds underneath
  float soft = pow(s, 1.45);

  float velBoost = 1.0 + abs(uVel)*1.35;
  // narrow ribbons brighten on scroll velocity
  float ribbons = streaks * (0.85 + abs(uVel)*0.6) * velBoost;
  float clouds  = soft * 0.52 * velBoost;

  float ink = mix(clouds, ribbons, 0.58);
  // horizontal elongation — stretch x, keep y soft
  float hx = 1.0 - smoothstep(0.35, 1.65, abs(uv.x));
  hx = pow(hx, 0.9);
  ink *= mix(0.72, 1.08, hx);

  // vignette — fade edges & bottom/top so hero video legibility stays
  float vx = smoothstep(1.42, 0.35, abs(uv.x));
  float vy = smoothstep(1.1, 0.2, abs(uv.y - 0.08));
  float vign = vx * vy;
  // extra fade at very top so hero text stays crisp
  float topFade = smoothstep(-0.9, -0.15, uv.y);
  vign *= mix(0.65, 1.0, topFade);

  ink *= vign;

  // grain — breaks banding, matches site's subtle grain overlay
  float grain = hash(frag*0.37 + t*60.0) * 0.06;
  ink += grain * vign * 0.35;

  // shape alpha — keep it veiled, never full white on black
  float alpha = ink * uOpacity * 1.35;
  alpha = clamp(alpha, 0., 0.95);
  // soft knee so brights bloom
  float bloom = pow(ink, 0.9) * 0.85;
  vec3 col = vec3(bloom * vign);
  // tiny warm bias removed — stay monochrome

  fragColor = vec4(col, alpha);
}
`;

// WebGL1 fallback shaders (no 300 es)
const VS_100 = `attribute vec2 aPos; void main(){ gl_Position=vec4(aPos,0.,1.); }`;
const FS_100 = `precision highp float;
uniform float uTime; uniform vec2 uRes; uniform float uScroll; uniform float uVel; uniform vec2 uPointer; uniform float uOpacity;
float hash(vec2 p){ p=fract(p*vec2(123.34,345.45)); p+=dot(p,p+33.33); return fract(p.x*p.y); }
float noise(vec2 p){ vec2 i=floor(p),f=fract(p); float a=hash(i),b=hash(i+vec2(1.,0.)),c=hash(i+vec2(0.,1.)),d=hash(i+vec2(1.,1.)); vec2 u=f*f*(3.-2.*f); return mix(mix(a,b,u.x), mix(c,d,u.x), u.y); }
float fbm(vec2 p){ float v=0.; float a=0.5; for(int i=0;i<4;i++){ v+=a*noise(p); p*=2.07; a*=0.5; } return v; }
void main(){
  vec2 frag=gl_FragCoord.xy; vec2 uv=(frag-0.5*uRes)/uRes.y; uv*=1.05;
  vec2 pm=uPointer*2.-1.; pm.x*=uRes.x/uRes.y; float pd=length(uv-pm*0.32); float pull=exp(-pd*1.4)*0.22; uv-=normalize(uv-pm*0.32+0.001)*pull;
  float drift=uScroll*0.9; uv.y+=drift*0.55; uv.x+=sin(uv.y*0.9+uTime*0.06)*0.04*(1.+abs(uVel)*0.8);
  float t=uTime*0.055;
  vec2 q=vec2(fbm(uv*0.95+vec2(0.,t*0.9)), fbm(uv*0.95+vec2(1.7,2.3)+t*0.7));
  vec2 r=vec2(fbm(uv*1.15+3.5*q+vec2(1.3,9.2)+t*0.55), fbm(uv*1.15+3.5*q+vec2(8.3,2.8)+t*0.48));
  vec2 warpUV=uv*1.35+4.0*r+vec2(0.,t*0.08);
  float s=fbm(warpUV); float streaks=smoothstep(0.46,0.72,s)*smoothstep(0.88,0.62,s); streaks=pow(streaks,0.85); float soft=pow(s,1.45);
  float velBoost=1.+abs(uVel)*1.35; float ribbons=streaks*(0.85+abs(uVel)*0.6)*velBoost; float clouds=soft*0.52*velBoost; float ink=mix(clouds,ribbons,0.58);
  float hx=1.-smoothstep(0.35,1.65,abs(uv.x)); hx=pow(hx,0.9); ink*=mix(0.72,1.08,hx);
  float vx=smoothstep(1.42,0.35,abs(uv.x)); float vy=smoothstep(1.1,0.2,abs(uv.y-0.08)); float vign=vx*vy; float topFade=smoothstep(-0.9,-0.15,uv.y); vign*=mix(0.65,1.,topFade);
  ink*=vign; float grain=hash(frag*0.37+t*60.)*0.06; ink+=grain*vign*0.35;
  float alpha=clamp(ink*uOpacity*1.35,0.,0.95); float bloom=pow(ink,0.9)*0.85; vec3 col=vec3(bloom*vign);
  gl_FragColor=vec4(col,alpha);
}
`;

function compile(gl: WebGLRenderingContext | WebGL2RenderingContext, type: number, src: string) {
  const s = gl.createShader(type)!;
  gl.shaderSource(s, src);
  gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(s);
    console.error("[webgl-field] shader compile failed", log, src.slice(0, 400));
    gl.deleteShader(s);
    return null;
  }
  return s;
}

export function WebGLField({
  opacity = 0.38,
  interactive = true,
  className,
  fpsBadge = false,
}: {
  opacity?: number;
  interactive?: boolean;
  className?: string;
  fpsBadge?: boolean;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [unsupported, setUnsupported] = useState(false);
  const [fps, setFps] = useState(60);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const coarse = window.matchMedia("(pointer: coarse)").matches;
    if (reduce) {
      // still render one static frame, then stop — keeps the veil without motion cost
    }

    let gl: WebGLRenderingContext | WebGL2RenderingContext | null = null;
    let isWebGL2 = false;
    try {
      gl = canvas.getContext("webgl2", { alpha: false, antialias: false, powerPreference: "high-performance", premultipliedAlpha: false }) as WebGL2RenderingContext;
      if (gl) isWebGL2 = true;
      if (!gl) gl = (canvas.getContext("webgl", { alpha: false, antialias: false, powerPreference: "high-performance" }) || canvas.getContext("experimental-webgl", { alpha: false } as any)) as WebGLRenderingContext | null;
    } catch {}
    if (!gl) {
      setUnsupported(true);
      return;
    }

    const vsSrc = isWebGL2 ? VS_300 : VS_100;
    const fsSrc = isWebGL2 ? FS_300 : FS_100;

    const vs = compile(gl, gl.VERTEX_SHADER, vsSrc);
    const fs = compile(gl, gl.FRAGMENT_SHADER, fsSrc);
    if (!vs || !fs) { setUnsupported(true); return; }

    const prog = gl.createProgram()!;
    gl.attachShader(prog, vs);
    gl.attachShader(prog, fs);
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      console.error("[webgl-field] link failed", gl.getProgramInfoLog(prog));
      setUnsupported(true);
      return;
    }
    gl.useProgram(prog);

    // fullscreen triangle
    const buf = gl.createBuffer()!;
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    const locPos = gl.getAttribLocation(prog, "aPos");
    gl.enableVertexAttribArray(locPos);
    gl.vertexAttribPointer(locPos, 2, gl.FLOAT, false, 0, 0);

    const uTime = gl.getUniformLocation(prog, "uTime")!;
    const uRes = gl.getUniformLocation(prog, "uRes")!;
    const uScroll = gl.getUniformLocation(prog, "uScroll")!;
    const uVel = gl.getUniformLocation(prog, "uVel")!;
    const uPointer = gl.getUniformLocation(prog, "uPointer")!;
    const uOpacity = gl.getUniformLocation(prog, "uOpacity")!;

    // state
    let raf = 0;
    let w = 0, h = 0, dpr = 1;
    let scroll = 0, scrollTarget = 0, vel = 0, velSmooth = 0;
    let px = 0.5, py = 0.5, pxT = 0.5, pyT = 0.5;
    let timeScale = reduce ? 0.14 : 1;
    let hidden = false;
    let frames = 0, lastFpsT = performance.now();

    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      w = Math.max(1, Math.round(rect.width * dpr));
      h = Math.max(1, Math.round(rect.height * dpr));
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w; canvas.height = h;
      }
      (gl as any).viewport(0, 0, w, h);
      // keep css size crisp
      canvas.style.width = rect.width + "px";
      canvas.style.height = rect.height + "px";
    };

    const onScroll = () => {
      const max = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
      scrollTarget = Math.min(1, Math.max(0, window.scrollY / max));
    };
    const onLerped = (e: Event) => {
      const y = (e as CustomEvent).detail as number;
      const max = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
      scrollTarget = Math.min(1, Math.max(0, y / max));
    };
    const onPointer = (e: PointerEvent) => {
      if (!interactive || coarse || reduce) return;
      pxT = e.clientX / window.innerWidth;
      pyT = 1 - e.clientY / window.innerHeight;
    };

    resize();
    onScroll();
    window.addEventListener("resize", resize);
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("smooth-scroll:lerped", onLerped as EventListener);
    if (interactive && !coarse && !reduce) window.addEventListener("pointermove", onPointer, { passive: true });
    const onVis = () => { hidden = document.hidden; if (!hidden && !raf) raf = requestAnimationFrame(tick); };
    document.addEventListener("visibilitychange", onVis);

    // reduced-motion: render one frame and stop drifting after settling
    let stillFrames = 0;

    const tick = () => {
      raf = requestAnimationFrame(tick);
      if (hidden) return;
      // smooth scroll + pointer
      const lerp = reduce ? 0.04 : 0.065;
      scroll += (scrollTarget - scroll) * 0.09;
      vel = scrollTarget - scroll;
      velSmooth += (vel * 12 - velSmooth) * 0.12;
      const vNorm = Math.max(-1, Math.min(1, velSmooth));
      px += (pxT - px) * lerp;
      py += (pyT - py) * lerp;

      const t = performance.now() * 0.001 * timeScale;
      // stop updating time after a while in reduced mode (static veil)
      if (reduce) {
        stillFrames++;
        if (stillFrames > 110) {
          // freeze — keep last frame, stop RAF to save battery
          cancelAnimationFrame(raf); raf = 0 as any;
          return;
        }
      }

      (gl as any).uniform1f(uTime, t);
      (gl as any).uniform2f(uRes, w, h);
      (gl as any).uniform1f(uScroll, scroll);
      (gl as any).uniform1f(uVel, vNorm);
      (gl as any).uniform2f(uPointer, px, py);
      (gl as any).uniform1f(uOpacity, opacity);

      (gl as any).drawArrays((gl as any).TRIANGLES, 0, 3);

      const err = (gl as any).getError?.();
      if (err && err !== 0) console.warn("[webgl-field] gl error", err);

      frames++;
      const now = performance.now();
      if (now - lastFpsT > 720) {
        const f = Math.round((frames * 1000) / (now - lastFpsT));
        if (fpsBadge) setFps(f);
        frames = 0; lastFpsT = now;
      }
    };

    // initial draw before RAF
    (gl as any).uniform1f(uOpacity, opacity);
    raf = requestAnimationFrame(tick);

    // cleanup
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", resize);
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("smooth-scroll:lerped", onLerped as EventListener);
      window.removeEventListener("pointermove", onPointer as any);
      document.removeEventListener("visibilitychange", onVis);
      try { gl.getExtension("WEBGL_lose_context")?.loseContext(); } catch {}
    };
  }, [opacity, interactive, fpsBadge]);

  if (unsupported) return null;

  return (
    <>
      <canvas
        ref={canvasRef}
        aria-hidden
        className={`pointer-events-none fixed inset-0 -z-10 h-[100vh] w-screen ${className ?? ""}`}
        style={{ opacity: 1 }}
      />
      {fpsBadge ? (
        <div className="pointer-events-none fixed bottom-3 right-3 z-50 rounded-full border border-white/10 bg-black/70 px-2.5 py-1 font-mono text-[10px] tracking-wide text-white/70 backdrop-blur">
          {fps} fps · WebGL
        </div>
      ) : null}
      {/* CSS veil fallback — if JS disabled, keep pure black */}
      <noscript>
        <style>{`[data-webgl-field]{display:none}`}</style>
      </noscript>
    </>
  );
}
