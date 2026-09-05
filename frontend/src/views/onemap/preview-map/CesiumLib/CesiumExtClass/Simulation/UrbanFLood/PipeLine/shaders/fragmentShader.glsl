precision highp float;
uniform int u_component;
uniform float u_time;
uniform bool u_showFlow;
uniform bool u_renderAttr;
uniform float u_roughness;

in vec2 f_uv;
in float f_pipeTypes;
flat in float f_attributeIndex;
in float f_pipeDir;
flat in float f_depth;
flat in vec3 f_boxMax;
flat in vec3 f_boxMin;

in vec3 v_modelPos;
in vec3 v_cameraModelPos;
in vec3 v_viewDirModel;
float hash(float n) {
    return fract(sin(n) * 43758.5453123);
}

float noise(vec3 x) {
    vec3 p = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);

    float n = p.x + p.y * 57.0 + p.z * 113.0;
    return mix(mix(mix(hash(n + 0.0), hash(n + 1.0), f.x), mix(hash(n + 57.0), hash(n + 58.0), f.x), f.y), mix(mix(hash(n + 113.0), hash(n + 114.0), f.x), mix(hash(n + 170.0), hash(n + 171.0), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float f = 0.0;
    float weight = 0.5;
    for(int i = 0; i < 4; i++) {
        f += weight * noise(p);
        p *= 2.0;
        weight *= 0.5;
    }
    return f;
}

vec3 renderWaterfallVolume(vec3 rayOrigin, vec3 rayDir, vec3 boxMin, vec3 boxMax) {
    vec3 invDir = 1.0 / rayDir;
    vec3 tbot = invDir * (boxMin - rayOrigin);
    vec3 ttop = invDir * (boxMax - rayOrigin);

    vec3 tmin = min(ttop, tbot);
    vec3 tmax = max(ttop, tbot);

    float tEntry = max(max(tmin.x, tmin.y), tmin.z);
    float tExit = min(min(tmax.x, tmax.y), tmax.z);

    if(tEntry > tExit || tExit < 0.0) {
        return vec3(0.0);
    }
    tEntry = max(tEntry, 0.0);

    float flowTime = float(czm_frameNumber) * 0.016 / 2.0;

    float mainFlowSpeed = 2.5;
    float turbulenceSpeed = 4.0;
    float detailSpeed = 3.0;

    const int STEPS = 24;
    float stepSize = (tExit - tEntry) / float(STEPS);

    vec3 accumulatedColor = vec3(0.0);
    float accumulatedAlpha = 0.0;

    for(int i = 0; i < STEPS; i++) {
        if(accumulatedAlpha > 0.95)
            break;

        float t = tEntry + stepSize * (float(i) + 0.5);
        vec3 samplePos = rayOrigin + rayDir * t;

        vec3 uvw = (samplePos - boxMin) / (boxMax - boxMin);

        uvw.z = 1.0 - uvw.z;

        // =========================================================
        const vec2 kDriftDir = normalize(vec2(-1.0, 1.));
        const float kDriftMax = 0.72;
        const float kDriftExp = 2.0;

        vec2 drift = kDriftDir * (kDriftMax * pow(clamp(1. - uvw.z, 0.0, 1.0), kDriftExp));

        vec2 xyShifted = uvw.xy - drift;
        // =========================================================

        vec2 centerXY = vec2(0.5, 0.5);
        vec2 offsetXY = xyShifted - centerXY;
        float radialDist = length(offsetXY);
        float angle = atan(offsetXY.y, offsetXY.x);

        vec3 mainFlowPos = vec3(radialDist * 10.0,
        angle * 3.0,
        uvw.z * 2.5 + flowTime * mainFlowSpeed
        );
        float mainFlow = fbm(mainFlowPos);

        vec3 turbulencePos = vec3(radialDist * 18.0, angle * 5.0 + flowTime * 2.0,
        uvw.z * 4.0 + flowTime * turbulenceSpeed);
        turbulencePos.x += sin(uvw.z * 8.0 + flowTime * 2.0) * 0.5;
        float turbulence = fbm(turbulencePos);

        vec3 detailPos = vec3(xyShifted.x * 20.0, xyShifted.y * 20.0, uvw.z * 5.0 + flowTime * detailSpeed);
        float spiralRotation = uvw.z * 3.14159 + flowTime * 0.3;
        detailPos.xy = mat2(cos(spiralRotation), -sin(spiralRotation), sin(spiralRotation), cos(spiralRotation)) * detailPos.xy;
        float detail = fbm(detailPos);

        float density = mainFlow * 0.5 + turbulence * 0.3 + detail * 0.2;

        float fallSpeed = 1.0 + uvw.z * 0.5;
        float topDensity = smoothstep(0.05, 0.15, uvw.z) * 1.5;
        float midDensity = smoothstep(0.1, 0.3, uvw.z) * smoothstep(0.9, 0.7, uvw.z);
        float bottomSplash = smoothstep(0.8, 1.0, uvw.z) * (0.5 + 0.5 * sin(flowTime * 5.0 + angle * 6.0));
        float heightFalloff = max(topDensity, midDensity) + bottomSplash;

        float baseRadius = mix(0.05, 0.25, pow(uvw.z, 0.7));

        float radialNoise1 = fbm(vec3(angle * 4.0, uvw.z * 3.0 + flowTime * 1.5, radialDist * 8.0)) * 0.08;
        float angularNoise = fbm(vec3(angle * 8.0 + flowTime * 1.0, uvw.z * 2.5, radialDist * 10.0)) * 0.06;
        float detailNoise = fbm(vec3(angle * 12.0, uvw.z * 4.0 + flowTime * 2.5, radialDist * 15.0)) * 0.04;

        float dynamicRadius = baseRadius + radialNoise1 + angularNoise + detailNoise;

        float radialFalloff = smoothstep(dynamicRadius + 0.1, dynamicRadius - 0.05, radialDist);

        float coreDensity = smoothstep(baseRadius * 0.6, baseRadius * 0.3, radialDist) * 0.3;
        radialFalloff += coreDensity;

        density *= heightFalloff * radialFalloff * fallSpeed;

        float shimmer = 0.5 + 0.5 * sin(flowTime * 3.0 + radialDist * 15.0 + angle * 4.0);
        float threshold = mix(0.25, 0.45, shimmer * 0.3);
        density = smoothstep(threshold, threshold + 0.5, density);

        if(density > 0.01) {
            vec3 sprayColor = vec3(0.85, 0.92, 1.0);
            float heightColor = uvw.z;
            vec3 topTint = vec3(0.7, 0.85, 1.0);
            vec3 bottomTint = vec3(0.95, 0.98, 1.0);
            sprayColor = mix(topTint, bottomTint, heightColor);

            float radialColor = smoothstep(0.0, baseRadius * 0.8, radialDist);
            sprayColor = mix(vec3(1.0), sprayColor, radialColor * 0.4);
            sprayColor = mix(sprayColor, vec3(1.0), density * 0.3);

            float sunlight = 0.6 + 0.4 * fbm(vec3(radialDist * 8.0, angle * 3.0, flowTime * 0.5));
            float rim = pow(1.0 - density, 2.0) * 0.5;
            float depthDarken = mix(1.0, 0.7, density);
            float radialHighlight = smoothstep(baseRadius * 0.5, 0.0, radialDist) * 0.3;

            float lightIntensity = sunlight * depthDarken + rim + radialHighlight;
            vec3 litColor = sprayColor * lightIntensity * 1.5;

            float flowDetail = fbm(vec3(radialDist * 12.0, angle * 6.0, uvw.z * 6.0 - flowTime * 8.0));
            flowDetail = smoothstep(0.4, 0.6, flowDetail) * 0.15;
            litColor += vec3(flowDetail);

            float sampleAlpha = density * 0.2;
            sampleAlpha *= 0.7 + 0.3 * density;
            sampleAlpha *= mix(1.0, 0.6, smoothstep(baseRadius * 0.5, baseRadius, radialDist));

            accumulatedColor += litColor * sampleAlpha * (1.0 - accumulatedAlpha);
            accumulatedAlpha += sampleAlpha * (1.0 - accumulatedAlpha);
        }
    }

    if(accumulatedAlpha > 0.0) {
        vec3 ambientTint = vec3(0.9, 0.95, 1.0);
        accumulatedColor = mix(accumulatedColor, ambientTint, 0.1);
    }

    return accumulatedColor;
}

// ==== Overflow toggles ====

#ifndef OVERFLOW_LEVEL
#endif
#ifndef OVERFLOW_SPREAD
#endif

// ==== Math & utils ====
#define PI 3.14159265359

//speed
const float speed = 0.2;
const float speed_x = 0.3;
const float speed_y = 0.3;

// refraction
const float emboss = 0.50;
const float intensity = 2.4;
const int steps = 8;
const float frequency = 6.0;
const int angle = 7; // better when a prime

// reflection
const float delta = 60.;
const float gain = 700.;
const float reflectionCutOff = 0.012;
const float reflectionIntensity = 200000.;

// ===================================================

float col_f(vec2 coord, float time) {
    float delta_theta = 2.0 * PI / float(angle);
    float col = 0.0;
    float theta = 0.0;
    for(int i = 0; i < steps; i++) {
        vec2 adjc = coord;
        theta = delta_theta * float(i);
        adjc.x += cos(theta) * time * speed + time * speed_x;
        adjc.y -= sin(theta) * time * speed - time * speed_y;
        col = col + cos((adjc.x * cos(theta) - adjc.y * sin(theta)) * frequency) * intensity;
    }

    return cos(col);
}
float hash11(float n) {
    return fract(sin(n) * 43758.5453123);
}

float noise2(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash11(dot(i, vec2(1., 57.)));
    float b = hash11(dot(i + vec2(1, 0), vec2(1., 57.)));
    float c = hash11(dot(i + vec2(0, 1), vec2(1., 57.)));
    float d = hash11(dot(i + vec2(1, 1), vec2(1., 57.)));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm2(vec2 p) {
    float f = 0.0, a = 0.5;
    mat2 M = mat2(1.6, 1.2, -1.2, 1.6);
    for(int i = 0; i < 5; i++) {
        f += a * noise2(p);
        p = M * p + 0.07;
        a *= 0.5;
    }
    return f;
}

float sdCircle(vec2 p, float r) {
    return length(p) - r;
}
float fillAA(float d) {
    float w = fwidth(d) + 1e-5;
    return 1.0 - smoothstep(0.0, w, d);
}

vec2 repeatX(vec2 p, float L) {
    p.x = mod(p.x + 0.5 * L, L) - 0.5 * L;
    return p;
}

// ===== Water in the trench (below slits) =====
vec3 waterShade(vec2 uv, float t) {
    float flow = fbm2(vec2(uv.x * 2.0 - t * 0.6, uv.y * 1.7 + t * 0.35));
    float rip = fbm2(uv * 7.0 + vec2(0.3, -t * 1.3));
    float caust = smoothstep(0.65, 0.95, flow * 0.8 + 0.2 * rip);
    vec3 water = mix(vec3(0.02, 0.07, 0.12), vec3(0.02, 0.12, 0.20), 0.6 + 0.4 * flow);
    water += 0.5 * pow(max(0.0, caust), 8.0);
    return water;
}

// ===== PBR (GGX) helpers =====
float saturate(float x) {
    return clamp(x, 0.0, 1.0);
}
vec3 saturate(vec3 v) {
    return clamp(v, 0.0, 1.0);
}

float D_GGX(float a2, float NdotH) {
    float d = (NdotH * NdotH) * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1e-6);
}
float V_SmithGGXCorrelated(float a2, float NdotV, float NdotL) {
    float gv = NdotL * sqrt(max(0.0, (NdotV - a2 * NdotV) * NdotV + a2));
    float gl = NdotV * sqrt(max(0.0, (NdotL - a2 * NdotL) * NdotL + a2));
    return 0.5 / max(gv + gl, 1e-6);
}
vec3 F_Schlick(vec3 F0, float VdotH) {
    float f = pow(1.0 - VdotH, 5.0);
    return F0 + (1.0 - F0) * f;
}

// Simple environment color (sky/ground) for IBL-like ambient
vec3 envColor(vec3 N) {
    vec3 sky = vec3(0.22, 0.27, 0.33);
    vec3 grn = vec3(0.10, 0.10, 0.11);
    float k = 0.5 * (N.z + 1.0); // up-facing gets more sky
    return mix(grn, sky, k);
}

// Brushed height map for normal/roughness variation
float brushedHeight(vec2 uv, float tilt) {
    float c = cos(tilt), s = sin(tilt);
    vec2 wuv = (mat2(c, -s, s, c) * uv) * vec2(18.0, 220.0);
    return fbm2(wuv);
}

// Derive pseudo-normal from height
vec3 pseudoNormal(vec2 uv, float tilt, float strength) {
    float h = brushedHeight(uv, tilt);
    float hx = dFdx(h), hy = dFdy(h);
    vec3 n = normalize(vec3(-hx * strength, -hy * strength, 1.0));
    return n;
}

// One-bounce direct-light + ambient PBR
vec3 pbrShade(
    vec3 albedo,
    float metallic,
    float roughness,
    vec3 N,
    vec3 V,
    vec3 L,
    vec3 lightColor,
    float ao
) {
    roughness = clamp(roughness, 0.04, 1.0);
    float a = roughness * roughness;
    float a2 = a * a;

    float NdotL = saturate(dot(N, L));
    float NdotV = saturate(dot(N, V));
    if(NdotL <= 0.0)
        return envColor(N) * albedo * ao * 0.2;

    vec3 H = normalize(L + V);
    float NdotH = saturate(dot(N, H));
    float VdotH = saturate(dot(V, H));

    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    vec3 F = F_Schlick(F0, VdotH);
    float D = D_GGX(a2, NdotH);
    float G = V_SmithGGXCorrelated(a2, NdotV, NdotL);

    vec3 spec = (D * G) * F / max(4.0 * NdotV * NdotL, 1e-6);
    vec3 kS = F;
    vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);

    vec3 diff = kD * albedo / PI;

    // Direct light
    vec3 Lo = (diff + spec) * lightColor * NdotL;

    // Ambient (very cheap IBL approx)
    vec3 amb = envColor(N);
    vec3 F_amb = F_Schlick(F0, NdotV);             // use NdotV for gloss bias
    vec3 specIBL = F_amb * (0.1 + 0.9 * (1.0 - roughness)); // shinier with lower roughness
    vec3 diffIBL = kD * albedo;

    vec3 L_amb = (diffIBL * amb * 0.5 + specIBL * amb * 0.5) * ao;

    return Lo + L_amb;
}

// ===== Scene params =====
const float segLen = 1.0;
const float frameTh = 0.035;
const float slitPitch = 0.085;
const float barW = 0.030;

//arrow

const int ARROW_V_STYLE = 1;
const int ARROW_LINE_STYLE = 2;

// Choose your arrow head style
const int ARROW_STYLE = ARROW_LINE_STYLE;
const float ARROW_TILE_SIZE = 128.0;

// How sharp should the arrow head be? Used
const float ARROW_HEAD_ANGLE = 45.0 * PI / 180.0;

// Used for ARROW_LINE_STYLE
const float ARROW_HEAD_LENGTH = ARROW_TILE_SIZE / 6.0;
const float ARROW_SHAFT_THICKNESS = 3.0;

// Computes the center pixel of the tile containing pixel pos
vec2 arrowTileCenterCoord(vec2 pos) {
    return (floor(pos / ARROW_TILE_SIZE) + 0.5) * ARROW_TILE_SIZE;
}

// v = field sampled at tileCenterCoord(p), scaled by the length
// desired in pixels for arrows
// Returns 1.0 where there is an arrow pixel.
float arrow(vec2 p, vec2 v) {
	// Make everything relative to the center, which may be fractional
    p -= arrowTileCenterCoord(p);

    float mag_v = length(v), mag_p = length(p);

    if(mag_v > 0.0) {
		// Non-zero velocity case
        vec2 dir_p = p / mag_p, dir_v = v / mag_v;

		// We can't draw arrows larger than the tile radius, so clamp magnitude.
		// Enforce a minimum length to help see direction
        mag_v = clamp(mag_v, 5.0, ARROW_TILE_SIZE / 2.0);

		// Arrow tip location
        v = dir_v * mag_v;

		// Define a 2D implicit surface so that the arrow is antialiased.
		// In each line, the left expression defines a shape and the right controls
		// how quickly it fades in or out.

        float dist;
        if(ARROW_STYLE == ARROW_LINE_STYLE) {
			// Signed distance from a line segment based on https://www.shadertoy.com/view/ls2GWG by 
			// Matthias Reitinger, @mreitinger

			// Line arrow style
            dist = max(
					// Shaft
            ARROW_SHAFT_THICKNESS / 4.0 -
                max(abs(dot(p, vec2(dir_v.y, -dir_v.x))), // Width
            abs(dot(p, dir_v)) - mag_v + ARROW_HEAD_LENGTH / 2.0), // Length

   			         // Arrow head
            min(0.0, dot(v - p, dir_v) - cos(ARROW_HEAD_ANGLE / 2.0) * length(v - p)) * 2.0 + // Front sides
                min(0.0, dot(p, dir_v) + ARROW_HEAD_LENGTH - mag_v)); // Back
        } else {
			// V arrow style
            dist = min(0.0, mag_v - mag_p) * 2.0 + // length
                min(0.0, dot(normalize(v - p), dir_v) - cos(ARROW_HEAD_ANGLE / 2.0)) * 2.0 * length(v - p) + // head sides
                min(0.0, dot(p, dir_v) + 1.0) + // head back
                min(0.0, cos(ARROW_HEAD_ANGLE / 2.0) - dot(normalize(v * 0.33 - p), dir_v)) * mag_v * 0.8; // cutout
        }

        return clamp(1.0 + dist, 0.0, 1.0);
    } else {
		// Center of the pixel is always on the arrow
        return max(0.0, 1.2 - mag_p);
    }
}

vec3 topImage(vec2 uv, vec4 attrs) {
    bool overflow = attrs.a > 0.;

    float minEdge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
    float frameMask = fillAA(minEdge - frameTh);
    float innerMask = 1.0 - frameMask;

    float m = mod(uv.x + 0.5 * slitPitch, slitPitch) - 0.5 * slitPitch;
    float dBar = abs(m) - barW * 0.5;
    float barMask = fillAA(dBar) * innerMask;

    float slitMask = clamp(1.0 - frameMask - barMask, 0.0, 1.0);

    float screwMarginX = 0.12;
    float screwMarginY = 0.10;
    float rScrew = 0.015;
    float dScrew = min(min(sdCircle(uv - vec2(frameTh + screwMarginX, frameTh + screwMarginY), rScrew), sdCircle(uv - vec2(1.0 - frameTh - screwMarginX, frameTh + screwMarginY), rScrew)), min(sdCircle(uv - vec2(frameTh + screwMarginX, 1.0 - frameTh - screwMarginY), rScrew), sdCircle(uv - vec2(1.0 - frameTh - screwMarginX, 1.0 - frameTh - screwMarginY), rScrew)));
    float screwMask = fillAA(dScrew) * innerMask;

    vec3 waterInner = waterShade((uv + vec2(0.0, float(czm_frameNumber) * 0.016 / 2.0 * 0.10)) * 0.8, float(czm_frameNumber) * 0.016 / 2.0);
    float aoBarEdge = 1.0 - 0.7 * smoothstep(0.0, 0.010, abs(dBar));
    vec3 col = waterInner * 0.0;

    // ===== PBR Metal shading for frame/bars/screws =====
    vec3 V = vec3(0.0, 0.0, 1.0);                    // top-down view
    vec3 L = normalize(vec3(-0.8, 0.6, 0.7));        // single key light
    vec3 lightColor = vec3(5.0, 5.0, 5.0);           // bright white light

    float tiltFrame = 0.10;
    float tiltBars = 0.05;
    float nStrength = 0.8;

    vec3 Nf = pseudoNormal(uv, tiltFrame, nStrength);
    vec3 Nb = pseudoNormal(uv * vec2(1.0, 3.0), tiltBars, nStrength);
    vec3 Ns = pseudoNormal(uv * 4.0, 0.0, nStrength);

    // Frame metal
    vec3 albedoF = vec3(0.40, 0.42, 0.45);
    float roughF = clamp(0.35 + 0.25 * (1.0 - brushedHeight(uv, tiltFrame)), 0.08, 0.95);
    vec3 colFrame = pbrShade(albedoF, 1.0, roughF, Nf, V, L, lightColor, 1.0);

    // Bars metal
    vec3 albedoB = vec3(0.38, 0.40, 0.43);
    float roughB = clamp(0.30 + 0.30 * (1.0 - brushedHeight(uv * vec2(1.0, 3.0), tiltBars)), 0.06, 0.95);
    vec3 colBars = pbrShade(albedoB, 1.0, roughB, Nb, V, L, lightColor, 1.0);

    // Screws metal
    vec3 albedoS = vec3(0.25, 0.26, 0.28);
    float roughS = 0.55;
    vec3 colScrew = pbrShade(albedoS, 1.0, roughS, Ns, V, L, lightColor, 1.0);

    col = mix(col, colFrame, frameMask);
    col = mix(col, colBars, barMask);
    col = mix(col, colScrew, screwMask);

    if(overflow) {
        float t = float(czm_frameNumber) * 0.016 / 2.0;

        float surge = 0.6 + 0.4 * sin(t * 0.6);
        float H = clamp(OVERFLOW_LEVEL * (0.65 + 0.35 * surge), 0.0, 1.0);

        float distToGapEdge = abs(abs(m) - barW * 0.5);
        float spreadFromGaps = exp(-distToGapEdge / max(OVERFLOW_SPREAD, 1e-3));

        float pond = smoothstep(-0.1, 0.65, 0.5 - uv.y);

        float coverMetal = barMask * 0.75 + frameMask * 0.55;

        float sourceOpen = slitMask;

        float sheetA = clamp(H * (0.55 * spreadFromGaps + 0.30 * pond + 0.15 * sourceOpen + 0.35 * coverMetal), 0.0, 1.0);

        vec3 surfW = waterShade(uv * 1.1 + vec2(0.0, -t * 0.08), t);

        float drown = smoothstep(0.35, 0.9, sheetA);
        col = mix(col, surfW, sheetA);
        col = mix(col, col * 0.9, 0.5 * drown);

        float time = t * 1.3;

        vec2 p = uv, c1 = p, c2 = p;
        float cc1 = col_f(c1, time);

        c2.x += 512. / delta;
        float dx = emboss * (cc1 - col_f(c2, time)) / delta;

        c2.x = p.x;
        c2.y += 512. / delta;
        float dy = emboss * (cc1 - col_f(c2, time)) / delta;

        c1.x += dx * 2.;
        c1.y = -(c1.y + dy * 2.);

        float alpha = 1. + dot(dx, dy) * gain;

        float ddx = dx - reflectionCutOff;
        float ddy = dy - reflectionCutOff;
        if(ddx > 0. && ddy > 0.)
            alpha = pow(alpha, ddx * ddy * reflectionIntensity);

        col *= (alpha);

        //arrow
        col *= (vec4(1.0) - arrow(uv * 1024., (attrs.gb - .5) * 2. * ARROW_TILE_SIZE * 0.4)).xyz;

    }

    // Gamma
    col = pow(saturate(col), vec3(0.4545));
    return col;
}

uniform sampler2D u_attrMap;
uniform sampler2D u_arrowTexture;

const float METALLIC = 0.7;
const vec3 BASE_METAL_COLOR = vec3(0.6, 0.65, 0.7);

float DistributionGGX(vec3 N, vec3 H, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;

    float nom = a2;
    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    denom = PI * denom * denom;

    return nom / max(denom, 0.0001);
}

float GeometrySchlickGGX(float NdotV, float roughness) {
    float r = (roughness + 1.0);
    float k = (r * r) / 8.0;

    float nom = NdotV;
    float denom = NdotV * (1.0 - k) + k;

    return nom / max(denom, 0.0001);
}

float GeometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
    float NdotV = max(dot(N, V), 0.0);
    float NdotL = max(dot(N, L), 0.0);
    float ggx2 = GeometrySchlickGGX(NdotV, roughness);
    float ggx1 = GeometrySchlickGGX(NdotL, roughness);

    return ggx1 * ggx2;
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 calculateNormal(vec2 uv) {
    float angle = uv.x * 2.0 * PI;
    return normalize(vec3(cos(angle), sin(angle), 0.0));
}

vec3 calculatePBR(vec3 albedo, vec3 normal, vec3 viewDir, float roughness, float metallic) {
    vec3 lightPositions[3];
    lightPositions[0] = vec3(5.0, 5.0, 5.0);
    lightPositions[1] = vec3(-5.0, 5.0, 5.0);
    lightPositions[2] = vec3(0.0, -5.0, 3.0);

    vec3 lightColors[3];
    lightColors[0] = vec3(1.0, 1.0, 1.0) * 300.0;
    lightColors[1] = vec3(1.0, 1.0, 1.0) * 150.0;
    lightColors[2] = vec3(1.0, 0.9, 0.8) * 100.0;

    vec3 F0 = vec3(0.04);
    F0 = mix(F0, albedo, metallic);

    vec3 Lo = vec3(0.0);

    for(int i = 0; i < 3; ++i) {
        vec3 L = normalize(lightPositions[i]);
        vec3 H = normalize(viewDir + L);
        float distance = length(lightPositions[i]);
        float attenuation = 1.0 / (distance * distance);
        vec3 radiance = lightColors[i] * attenuation;

            // Cook-Torrance BRDF
        float NDF = DistributionGGX(normal, H, roughness);
        float G = GeometrySmith(normal, viewDir, L, roughness);
        vec3 F = fresnelSchlick(max(dot(H, viewDir), 0.0), F0);

        vec3 numerator = NDF * G * F;
        float denominator = 4.0 * max(dot(normal, viewDir), 0.0) * max(dot(normal, L), 0.0) + 0.0001;
        vec3 specular = numerator / denominator;

        vec3 kS = F;
        vec3 kD = vec3(1.0) - kS;
        kD *= 1.0 - metallic;

        float NdotL = max(dot(normal, L), 0.0);
        Lo += (kD * albedo / PI + specular) * radiance * NdotL;
    }

    vec3 ambient = vec3(0.03) * albedo;
    vec3 color = ambient + Lo;

    color = color / (color + vec3(1.0));
    color = pow(color, vec3(1.0 / 2.2));

    return color;
}

vec3 generateMetalTexture(vec2 uv) {
    float circumStripes = sin(uv.x * 80.0) * 0.5 + 0.5;
    circumStripes = pow(circumStripes, 3.0) * 0.15;

    float axialNoise = fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453);
    axialNoise = axialNoise * 0.08;

    float weldSeam = smoothstep(0.48, 0.5, abs(uv.x - 0.5)) * 0.2;

    vec3 metalBase = BASE_METAL_COLOR;
    metalBase += vec3(circumStripes);
    metalBase += vec3(axialNoise);
    metalBase -= vec3(weldSeam);

    return metalBase;
}

float calculateSpecular(vec2 uv, float roughness) {
    float specular = pow(1.0 - abs(uv.x - 0.5) * 2.0, 8.0);
    specular *= (1.0 - roughness);
    return specular * 0.3;
}

vec3 calculateFlowEffect(float timeIdx_n, float pipeDir, vec3 baseColor) {
    float dist = abs(timeIdx_n - pipeDir);
    if(timeIdx_n > 0.0) {
        dist = abs(timeIdx_n - pipeDir);
    } else {
        dist = abs(pipeDir - timeIdx_n);
    }

    if(dist < 0.1) {
        float flowIntensity = 1.0 - (dist / 0.1);
        flowIntensity = pow(flowIntensity, 2.0);
        vec3 flowColor = vec3(0.8, 0.95, 1.0);
        return mix(baseColor, flowColor, flowIntensity * 0.7);
    }

    return baseColor;
}

vec3 calculateFlowArrow(float timeIdx_n, float pipeDir, vec2 uv, vec3 baseColor, float flowSpeed) {
    float arrowSpacing = 4.7;
    float arrowLength = 1.10;
    float speedScale = .25;

    float animOffset = -timeIdx_n * speedScale;

    float localPos = mod(pipeDir + animOffset, arrowSpacing);

    vec2 arrowUV;
    arrowUV.x = localPos / arrowLength;
    arrowUV.y = mod(uv.x * 6., 2.);

    if(arrowUV.x >= 0.0 && arrowUV.x <= 1.0 && arrowUV.y >= 0.0 && arrowUV.y <= 1.0) {
        if(sign(flowSpeed) < 0.)
            arrowUV.x = 1. - arrowUV.x;
        vec4 arrowSample = texture(u_arrowTexture, arrowUV);
        if(arrowSample.a < 0.1)
            return baseColor;

        vec3 arrowColor = vec3(0.3, 0.7, 1.0);

        float fadeIn = smoothstep(0.0, 0.1, arrowUV.x);
        float fadeOut = smoothstep(1.0, 0.9, arrowUV.x);
        float fade = fadeIn * fadeOut;

        float arrowMask = arrowSample.a * fade;

        vec3 glowColor = arrowColor * 1.5;
        vec3 finalArrowColor = mix(arrowColor, glowColor, arrowMask * 0.5);

        finalArrowColor = vec3(arrowUV, 0.);
        finalArrowColor = texture(u_arrowTexture, arrowUV).xyz;
        return finalArrowColor;

        return mix(baseColor, finalArrowColor, arrowMask * 0.8);
    }

    return baseColor;
}

vec3 generateConcreteTexture(vec2 uv) {
    vec3 baseColor = vec3(0.5, 0.52, 0.55);

    float noise1 = fract(sin(dot(uv * 50.0, vec2(12.9898, 78.233))) * 43758.5453);
    float noise2 = fract(sin(dot(uv * 120.0, vec2(93.989, 67.345))) * 28474.3847);
    float noise3 = fract(sin(dot(uv * 200.0, vec2(45.123, 89.456))) * 19384.7392);

    float roughNoise = noise1 * 0.5 + noise2 * 0.3 + noise3 * 0.2;

    float spots = smoothstep(0.7, 0.9, noise1) * 0.15;

    float microDetail = (noise2 - 0.5) * 0.1;

    vec3 concreteColor = baseColor;
    concreteColor += vec3(microDetail);
    concreteColor -= vec3(spots);
    concreteColor *= (0.85 + roughNoise * 0.3);

    return concreteColor;
}

vec3 getColorByValue(float value) {

    vec3 colorRamp[5];
    colorRamp[0] = vec3(55., 149., 196.) / 255.;
    colorRamp[1] = vec3(161., 194., 153.) / 255.;
    colorRamp[2] = vec3(250., 250., 102.) / 255.;
    colorRamp[3] = vec3(250., 146., 55.) / 255.;
    colorRamp[4] = vec3(232., 24., 21.) / 255.;
    value = clamp(value, 0.0, 1.0);

    float scaledValue = value * 4.0;

    int index = int(floor(scaledValue));

    float t = scaledValue - floor(scaledValue);

    vec3 color1, color2;

    if(index == 0) {
        color1 = colorRamp[0];
        color2 = colorRamp[1];
    } else if(index == 1) {
        color1 = colorRamp[1];
        color2 = colorRamp[2];
    } else if(index == 2) {
        color1 = colorRamp[2];
        color2 = colorRamp[3];
    } else { // index >= 3
        color1 = colorRamp[3];
        color2 = colorRamp[4];
    }

    return mix(color1, color2, t);
}

void main() {
    vec4 attrs = texelFetch(u_attrMap, ivec2(u_time, f_attributeIndex), 0);

    out_FragColor = vec4(1.0);

    float timeIdx = mod(czm_frameNumber * attrs.g, 47.);
      // timeIdx = u_time;
    float timeIdx_n = timeIdx / 47.;

    if(f_pipeTypes < 0.5) {
        float blend = clamp(u_roughness, 0.0, 1.0);
        float t = smoothstep(0.0, 1.0, blend);

        vec3 metalTex = generateMetalTexture(f_uv);
        vec3 concreteTex = generateConcreteTexture(f_uv);

        vec3 albedo = mix(metalTex, concreteTex, t);

        float metallic = mix(METALLIC, 0.0, t);

        float rMetal = 0.012;
        float rConcrete = 0.95;
        float alphaMix = mix(rMetal * rMetal, rConcrete * rConcrete, t);
        float roughness = sqrt(alphaMix);

        vec3 normal = calculateNormal(f_uv);
        vec3 viewDir = normalize(vec3(0.0, 0.0, 1.0));

        if(u_renderAttr) {
            vec3 heatColor = getColorByValue(attrs[u_component]);
            float attrMix = mix(0.45, 0.65, t);
            albedo = mix(albedo, heatColor, .9);
        }

        vec3 finalColor = calculatePBR(albedo, normal, viewDir, roughness, metallic);

        if(u_showFlow) {
            finalColor = calculateFlowArrow(timeIdx, f_pipeDir, f_uv, finalColor, attrs.g);
        }

    // —— 9) AO
        float ao = 0.7 + 0.3 * (1.0 - pow(abs(f_uv.x - 0.5) * 2.0, 2.0));
        finalColor *= ao;

        out_FragColor.rgb = finalColor;
    }

    if(f_pipeTypes > 0.5 && f_pipeTypes < 1.5) {
        vec3 concreteTexture = generateConcreteTexture(f_uv);

        vec3 normal = calculateNormal(f_uv);
        vec3 viewDir = normalize(vec3(0.0, 0.0, 1.0));

        vec3 finalColor = calculatePBR(concreteTexture, normal, viewDir, 1.0, 0.0);

        if(attrs.r > f_pipeDir) {
            vec3 heatColor = getColorByValue(attrs.r / 5.);
            heatColor = vec3(0.02, 0.12, 0.20);
            vec3 mixedAlbedo = mix(concreteTexture, heatColor, 0.9);
            finalColor = calculatePBR(mixedAlbedo, normal, viewDir, 1.0, 0.0);
        }

        float ao = 0.6 + 0.4 * (1.0 - pow(abs(f_uv.x - 0.5) * 2.0, 2.0));
        finalColor *= ao;

        if(f_depth - f_pipeDir < 0.01) {
            finalColor = topImage(f_uv, attrs);
        }

        out_FragColor.rgb = finalColor;
    }
    if(f_pipeTypes > 1.5) {

        vec3 rayOrigin = v_modelPos;

        mat4 invModel = inverse(czm_model);
        vec3 rayDirModel = normalize((invModel * vec4(v_viewDirModel, 1.0)).xyz);

        vec3 waterfallColor = renderWaterfallVolume(v_modelPos, normalize(v_viewDirModel), f_boxMin, f_boxMax);

        out_FragColor.rgb = waterfallColor;

        out_FragColor.a = clamp(length(waterfallColor) * 2.0, 0.0, 1.0);

        // vec2 hit = hitBox(v_cameraModelPos, normalize(v_viewDirModel), f_boxMin, f_boxMax);
        // out_FragColor.rgb = vec3((hit.x > hit.y ? 0. : 1.));
        // // out_FragColor.a = 1.;
    }
}