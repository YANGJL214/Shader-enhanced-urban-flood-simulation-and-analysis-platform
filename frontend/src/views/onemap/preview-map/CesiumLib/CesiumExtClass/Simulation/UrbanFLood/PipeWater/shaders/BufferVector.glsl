uniform float iTime;
uniform float iTimeDelta;
uniform int iFrame;
uniform vec2 resolution;
uniform float spawnRate;

uniform sampler2D vectorMap;

uniform sampler2D waterHeightMap;
uniform float flowScale;
uniform float alphaThreshold;

uniform float minStepPx;
uniform float maxSpeedPx;

const float decay = 0.007;
// const float spawnRate = 0.005;
const int smpDst = 2;

float frmAdj;
vec2 invRes;

float sampleMaskAt(vec2 pix) {
    vec2 uv = pix * invRes;
    return step(alphaThreshold, length(texture(waterHeightMap, uv).rgb));
}

vec2 sampleFlow(vec2 pix) {
    vec2 uv = pix * invRes;
    vec2 gb = texture(waterHeightMap, uv).gb;
    vec2 vel = gb * 2.0 - 1.0;
    vel.x = -vel.x;
    return -vel * flowScale;
}

// ========== hash ==========
const uvec4 shft = uvec4(14U, 15U, 16U, 17U);
const float imf = 1.0 / float(0xFFFFFFFFU);
vec4 bjhash128(vec4 p0) {
    uvec4 p = floatBitsToUint(p0);
    p ^= p >> shft;
    p *= uvec4(0xEAF649A9U, 0x6AF649A9U, 0x050C2D35U, 0xAAF649A9U);
    p ^= p.yzwx ^ p.zwxy;
    p ^= p >> shft.yzwx;
    p *= uvec4(0x21F0AAADU, 0x0D0C2D35U, 0xE8F649A9U, 0xD30332D3U);
    p ^= p.yzwx ^ p.zwxy;
    return vec4(p ^ p >> shft.wxyz) * imf;
}

vec2 sc(vec2 pos) {
    for(int i = -smpDst; i <= smpDst; i++) {
        for(int j = -smpDst; j <= smpDst; j++) {
            vec2 res = texture(vectorMap, (pos + vec2(i, j)) * invRes).xy;
            if(all(lessThan(abs(res - pos), vec2(0.5))))
                return res;
        }
    }
    return vec2(0.0);
}

vec4 ss(vec2 pos, vec2 scr) {
    float frame = float(iFrame);
    vec4 hash = bjhash128(vec4(pos, frame, 1.738765));

    if(hash.w <= spawnRate * frmAdj * 800.0 / resolution.x && sampleMaskAt(pos) > 0.5) {
        scr = pos + hash.xy;
    }
    if(!any(greaterThan(scr, vec2(0.0))))
        return vec4(0.0);

    vec2 v = sampleFlow(scr);
    float stepLen = length(v) * frmAdj;

    if(stepLen < minStepPx)
        return vec4(0.0);

    vec2 nextPos = scr + v * frmAdj;

    if(sampleMaskAt(nextPos) < 0.5)
        return vec4(0.0);

    float speedN = clamp(stepLen / maxSpeedPx, 0.0, 1.0);

    return vec4(nextPos, 1.0, speedN);
}

void main() {
    frmAdj = min(1.0, 144.0 * iTimeDelta);
    invRes = 1.0 / resolution;

    vec2 pos = gl_FragCoord.xy;
    vec2 uv = pos * invRes;

    if(sampleMaskAt(pos) < 0.5) {
        out_FragColor = vec4(0.0);
        return;
    }

    vec4 prev = texture(vectorMap, uv);
    float prevTrail = prev.z;
    float prevSpeed = prev.w;

    vec2 scRes = sc(pos);

    vec4 adv = ss(pos, scRes);          // adv = (nextPos.xy, aliveFlag, speedN)
    bool isHead = adv.z > 0.5;

    float trail = isHead ? 1.0 : max(0.0, prevTrail - decay * frmAdj);

    float speedOut = isHead ? adv.w : prevSpeed;

    vec2 xyOut = isHead ? adv.xy : vec2(0.0);

    out_FragColor = vec4(xyOut, trail, speedOut);
}
