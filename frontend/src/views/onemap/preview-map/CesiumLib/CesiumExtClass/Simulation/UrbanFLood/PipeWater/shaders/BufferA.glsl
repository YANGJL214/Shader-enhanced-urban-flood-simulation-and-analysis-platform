
// compute Terrain and update water level 1st pass
uniform sampler2D TerrainWaterMap;
uniform sampler2D OutFlow;
uniform sampler2D heightMap;
uniform sampler2D waterHeightMap;
uniform float iTime;
uniform int iFrame;
uniform vec4 waterAdd;
uniform vec2 resolution;

vec2 readHeight(ivec2 p) {
    p = clamp(p, ivec2(0), ivec2(ivec2(resolution) - 1));
    return texelFetch(TerrainWaterMap, p, 0).xy;
}

float readTerrain(ivec2 p) {
    p = clamp(p, ivec2(0), ivec2(ivec2(resolution) - 1));
    return texelFetch(heightMap, p, 0).r;
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

vec4 readOutFlow(ivec2 p) {
    if(p.x < 0 || p.y < 0 || p.x >= ivec2(resolution).x || p.y >= ivec2(resolution).y)
        return vec4(0);
    return texelFetch(OutFlow, p, 0);
}

float readinitWater(ivec2 p) {
    vec2 st = vec2(p) / resolution;
    float waterDepth = texture(waterHeightMap, st).r;

    waterDepth = texelFetch(waterHeightMap, p, 0).r;

    return waterDepth;
}

void main() {
   // Outside ?
    if(gl_FragCoord.x >= resolution.x || gl_FragCoord.y >= resolution.y)
        discard;

   // Terrain
    vec2 uv = gl_FragCoord.xy / resolution;
    float t = iTime / transitionTime;
    float terrainElevation = readTerrain(ivec2(gl_FragCoord.xy));
   // Water
    float waterDept = initialWaterLevel;
  //  if(terrainElevation<0.5) waterDept =  initialWaterLevel;
  //  else waterDept=0.;

    float canFlow;
    float fortranData;

    if(iFrame != 0) {
        ivec2 p = ivec2(gl_FragCoord.xy);
        vec2 height = readHeight(p);
        vec4 OutFlow = texelFetch(OutFlow, p, 0);
        float totalOutFlow = OutFlow.x + OutFlow.y + OutFlow.z + OutFlow.w;
        float totalInFlow = 0.0;
        totalInFlow += readOutFlow(p + ivec2(1, 0)).z;
        totalInFlow += readOutFlow(p + ivec2(0, 1)).w;
        totalInFlow += readOutFlow(p + ivec2(-1, 0)).x;
        totalInFlow += readOutFlow(p + ivec2(0, -1)).y;
        waterDept = height.y - totalOutFlow + totalInFlow;

      //  waterDept = texelFetch(waterHeightMap, p, 0).r *20. / heightRange;

      //  if(waterAdd.w==1. && int(waterAdd.x) == p.x && int(waterAdd.y) == p.y)
      //      waterDept += waterAdd.z;

      //  if(readinitWater(ivec2(gl_FragCoord.xy))>0. && iFrame<7) waterDept += 0.0002;

      // waterDept = blur(waterHeightMap, p).r *20. / heightRange *30.;
      // if(waterDept!=0.)waterDept+=0.001;

      // if(readinitWater(p)>0.1 && waterDept<0.00002) waterDept+= 0.000002;
      // else waterDept-= 0.000002;

        if(readinitWater(p) > 0.)
            waterDept = readinitWater(p) / 20.;
      // else waterDept-= 0.000002;

        canFlow = readCanFLow(p);
      // fortranData = texelFetch(waterHeightMap, p, 0).r;
        fortranData = blur(waterHeightMap, p).r;
    }

    out_FragColor = vec4(terrainElevation, waterDept, canFlow, fortranData);
}
