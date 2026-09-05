
// Created by David Gallardo - xjorma/2021
// License Creative Commons Attribution-NonCommercial-ShareAlike 3.0
#define AA
#define GAMMA 1
uniform sampler2D iChannel0;
// uniform sampler2D iChannel1;
uniform sampler2D heightMap;
uniform sampler2D waterHeightMap;
uniform vec2 dataResolution;
uniform int vectorScale;
uniform sampler2D waterVectorMap;
uniform sampler2D waterFlowMap;
uniform sampler2D fluidMap;
uniform vec4 waterColor;
uniform vec4 lightWaterColor;
uniform vec2 iResolution;
uniform float iTime;
uniform int iFrame;
uniform bool renderTerrain;
uniform bool renderHeatMap;
uniform bool renderParticle;
uniform bool renderVectorField;
uniform bool renderOriginData;
uniform float waterOpacity;
uniform float maxWaterDepth;
uniform float minDepthThreshold;
uniform float maxDepthThreshold;
in vec3 vo;
in vec3 vd;
in vec2 v_st;
const vec3 light = vec3(0., 4., 2.);
const float boxHeight = 0.45;

vec2 modelToUv(in vec3 p) {
  vec2 uv = p.xz + vec2(.5);
  return clamp(uv, vec2(0.0), (iResolution - vec2(0.5)) / iResolution);
}

ivec2 modelToPixel(in vec3 p, in vec2 resolution) {
  vec2 uv = clamp(p.xz + vec2(.5), vec2(0.0), (resolution - vec2(0.5)) / resolution);
  return ivec2(uv * resolution);
}

bool isDepthInThreshold(float normalizedDepth) {
  float depth = normalizedDepth * max(maxWaterDepth, 0.000001);
  return depth >= minDepthThreshold && depth <= maxDepthThreshold;
}

vec2 getHeight(in vec3 p) {
  vec2 h = texture(iChannel0, modelToUv(p)).xy;
  h.y += h.x;
  return h - boxHeight;
}

vec2 getAttr(in vec3 p) {
  vec2 h = texture(iChannel0, modelToUv(p)).zw;
  return h;
}

vec2 getWaterHeight(in vec3 p) {
  vec2 h = texture(waterHeightMap, modelToUv(p)).xy;
  return h;
}

vec3 getNormal(in vec3 p, int comp) {
  vec2 d = 1.0 / iResolution.xy;
  float hMid = getHeight(p)[comp];
  float hRight = getHeight(p + vec3(d.x, 0, 0))[comp];
  float hTop = getHeight(p + vec3(0, 0, d.y))[comp];
  return normalize(cross(vec3(0, hTop - hMid, d.y), vec3(d.x, hRight - hMid, 0)));
}

vec4 terrainColor(in vec3 p, in vec3 n, out float spec) {
  return lightWaterColor;
}

vec3 undergroundColor(float d) {
  vec3 color[4] = vec3[](vec3(0.5, 0.45, 0.5), vec3(0.40, 0.35, 0.25), vec3(0.55, 0.50, 0.4), vec3(0.45, 0.30, 0.20));
  d *= 6.0;
  d = min(d, 3.0 - 0.001);
  float fr = fract(d);
  float fl = floor(d);
  return mix(color[int(fl)], color[int(fl) + 1], fr);
}

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

vec3 speedToColor(float t) {
  t = clamp(t, 0.0, 1.0);

  float hue = 0.66 * (1.0 - t);
  return hsv2rgb(vec3(hue, 1.0, 1.0));

}

vec4 getVectorColor(vec4 vm) {
  float trailGamma = 0.7;
  float headBoost = 1.3;
  float trailOpacity = 0.9; 
  float trail = vm.z;
  float trailP = pow(clamp(trail, 0.0, 1.0), trailGamma);

  float speedN = vm.w;

  vec3 baseColor = speedToColor(speedN * 25.);

  float headMask = smoothstep(0.90, 1.00, trail);
  vec3 shaded = baseColor * mix(1.0, headBoost, headMask);

  vec3 rgb = shaded;
  float a = trailOpacity * trailP;
  return vec4(rgb, a);
}

vec3 getDefaultWaterColor(float normalizedDepth) {
  float depthMix = smoothstep(0.0, 1.0, clamp(normalizedDepth, 0.0, 1.0));
  return mix(lightWaterColor.xyz, waterColor.xyz, depthMix);
}

vec4 Render(in vec3 ro, in vec3 rd) {
  vec3 n;
  vec3 rayDir = normalize(rd);
  vec2 ret = hitBox(ro, rayDir);
  if(ret.x > ret.y)
    discard;
  ret.x = max(ret.x, 0.0);
  vec3 p = ro + ret.x * rayDir;

  if(ret.x > 0.0) {
    vec3 pi = ro + rd * ret.x;
    vec4 tc;
    tc.a = 1.;
    vec3 tn;
    float tt = ret.x;
    vec2 h = getHeight(pi);
    float spec;
    if(pi.y < h.x) {
      tn = n;
      tc.xyz = undergroundColor(h.x - pi.y);
      tc.a = 0.;
    } else {
      for(int i = 0; i < 200; i++) {
        vec3 p = ro + rd * tt;
        float h = p.y - getHeight(p).x;
        if(h < 0.00002 || tt > ret.y)
          break;
        tt += h * 0.4 * .25;
      }
      tn = getNormal(ro + rd * tt, 0);
      tc = terrainColor(ro + rd * tt, tn, spec);

          //  tc.xyz = getColorByValue(getHeight(ro + rd * tt).y);
    }
    {
      vec3 lightDir = normalize(light - (ro + rd * tt));
      tc.xyz = tc.xyz * (max(0.0, dot(lightDir, tn)) + 0.3);
      spec *= pow(max(0., dot(lightDir, reflect(rd, tn))), 10.0);
      tc.xyz += spec;
    }
    if(tt > ret.y) {
      tc.xyz = vec3(0, 0, 0.4);
    }
    float wt = ret.x;
    h = getHeight(pi);
    vec3 waterNormal;
    if(pi.y < h.y) {
      waterNormal = n;
    } else {
      for(int i = 0; i < 200; i++) {
        vec3 p = ro + rd * wt;
        float h = p.y - getHeight(p).y;
        if(h < 0.00002 || wt > min(tt, ret.y))
          break;
        wt += h * 0.4 * .25;
      }
      waterNormal = getNormal(ro + rd * wt, 1);

          //  tc.rgb = mix(tc.rgb,vec3(1.,0.,0.),clamp((tt -wt)*10.,0.,1.));
    }
    if(wt < ret.y) {
      float dist = (min(tt, ret.y) - wt);
      vec3 p = waterNormal;
      vec3 lightDir = normalize(light - (ro + rd * wt));
          //  tc.xyz = applyFog( tc.xyz, vec3(0, 0, 0.4), dist * 15.0 * 17.);
          //  tc.xyz = applyFog( tc.xyz, vec3(0.7176,0.5508,0.0703), dist * 15.0 * 17.);
          //  tc.xyz = applyFog( tc.xyz, vec3(0.359375,0.2578125,0.14453125), dist * 15.0 * 17.);
      tc.xyz = applyFog(tc.xyz, waterColor.xyz, dist * 15.0 * 17.);
          //  if(renderHeatMap) tc.xyz = applyFog( tc.xyz, getColorByValue(getAttr((ro + rd * wt)).y * 4.), dist * 15.0 * 17.);
      float spec = pow(max(0., dot(lightDir, reflect(rd, waterNormal))), 20.0);
      if(spec < .01)
        spec *= 5.;
    } else {
      discard;
    }
    float alpha = 1.;
       //  if( h.y > 0.) alpha = 1.;
       //  else alpha = 0.;
       //  return vec4(tc,alpha);
      //  if(tt -wt < minTotalFlow *1. || h.y <=minTotalFlow) tc.a=0.;
      //  else tc.a = clamp(h.y / .2 , 0. ,1.) ;

    vec4 waterInfo = texelFetch(waterHeightMap, modelToPixel(ro + rd * tt, iResolution), 0);
    // float heatV = texture(waterHeightMap, (ro + rd * tt).xz + .5).x;
    float heatV = waterInfo.r;
    if(waterInfo.a <= 0.001)
      discard;
    if(!isDepthInThreshold(heatV))
      discard;
    if(renderOriginData) {
          //  if(heatV < 0.00001 ) discard;
      return waterInfo;
    }
    if(renderHeatMap) {
      vec3 hColor = getColorByValue2(heatV);
      if(heatV < 0.0001)
        discard;
      return vec4(hColor, 1.);
    }
    if(renderParticle) {
      vec2 vectorResolution = iResolution * iResolution / dataResolution * float(vectorScale);
      vec4 particle = texelFetch(fluidMap, modelToPixel(ro + rd * tt, vectorResolution), 0);
      //  texelFetch(fluidMap, ivec2((ro + rd * tt + .5).xz * iResolution), 0);
      float particleStrength = clamp(max(max(particle.r, particle.g), particle.b), 0.0, 1.0);
      if(particle.a > 0.001 && particleStrength > 0.02) {
        tc.xyz = mix(tc.xyz, particle.rgb, particleStrength);
      }
    }

    tc.xyz = getDefaultWaterColor(heatV);
    bool hasVectorOverlay = false;

    // vec4 arrow = texelFetch(waterVectorMap, ivec2((ro + rd * tt + .5).xz * iResolution * iResolution / dataResolution * 8.), 0);
    vec2 vectorResolution = iResolution * iResolution / dataResolution * float(vectorScale);
    vec4 arrow = getVectorColor(texelFetch(waterVectorMap, modelToPixel(ro + rd * tt, vectorResolution), 0));
    if(renderVectorField && arrow.a > 0.1 && length(arrow.xyz) > 0.1) {
      tc.xyz = arrow.xyz;
      hasVectorOverlay = true;
    }
    float shallowOpacity = 1.0;
    vec2 hInfo = getHeight(ro + rd * tt);
    float waterDepth = max((hInfo.y - hInfo.x) * heightRange, 0.0);
    if(!renderTerrain && waterDepth < 0.51)
      shallowOpacity = clamp(waterDepth * 2.0, 0.0, 1.0);
    tc.a = hasVectorOverlay ? 1.0 : clamp(waterOpacity, 0.0, 1.0) * shallowOpacity;
    return tc;
  }
  discard;
}

vec3 vignette(vec3 color, vec2 q, float v) {
  color *= 0.3 + 0.8 * pow(16.0 * q.x * q.y * (1.0 - q.x) * (1.0 - q.y), v);
  return color;
}

void main() {
  // vec4 c = texture(fluidMap, v_st);
  // // out_FragColor = getVectorColor(v_st);
  // out_FragColor = vec4(c.xyz, 1.);
  // return;

  vec3 tot = vec3(0.0);
  vec3 rayDir = normalize(vd);
  vec4 col = Render(vo, rayDir);
  tot += col.rgb;
  out_FragColor = col;
}
