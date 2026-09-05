
// water level 2nd pass
uniform sampler2D iChannel0;
uniform sampler2D iChannel1;
uniform sampler2D waterHeightMap;
uniform float iTime;
uniform int iFrame;
uniform vec2 resolution;
vec2 readHeight(ivec2 p) {
    p = clamp(p, ivec2(0), ivec2(resolution) - ivec2(1));
    return texelFetch(iChannel0, p, 0).xy;
}

vec4 readOutFlow(ivec2 p) {
    if(p.x < 0 || p.y < 0 || p.x >= ivec2(resolution).x || p.y >= ivec2(resolution).y)
        return vec4(0);
    return texelFetch(iChannel1, p, 0);
}

float readCanFLow(ivec2 p) {
    int sum = 0;
    for(int i = -radius; i <= radius; i++) for(int j = -radius; j <= radius; j++) {
            if(texelFetch(waterHeightMap, p + ivec2(i, j), 0).r != 0.)
                sum++;
        }
    if(sum > radius * radius / 2)
        return 1.;
    return 0.;
}

void main() {
   // Outside ?
    if(gl_FragCoord.x >= resolution.x || gl_FragCoord.y >= resolution.y)
        discard;

   // Water
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 height = readHeight(p);
    vec4 OutFlow = texelFetch(iChannel1, p, 0);
    float totalOutFlow = OutFlow.x + OutFlow.y + OutFlow.z + OutFlow.w;
    float totalInFlow = 0.0;
    totalInFlow += readOutFlow(p + ivec2(1, 0)).z;
    totalInFlow += readOutFlow(p + ivec2(0, 1)).w;
    totalInFlow += readOutFlow(p + ivec2(-1, 0)).x;
    totalInFlow += readOutFlow(p + ivec2(0, -1)).y;
    float waterDept = height.y - totalOutFlow + totalInFlow;

  //  waterDept = texelFetch(waterHeightMap, p, 0).r *20. / heightRange *20.;
  // waterDept = blur(iChannel0, p).g;

    float canFlow = readCanFLow(p);

  //  float fortranData = texelFetch(waterHeightMap, p, 0).r;
    float fortranData = blur(waterHeightMap, p).r;

    out_FragColor = vec4(height.x, waterDept, canFlow, fortranData);
}
