uniform float iTime;
uniform vec2 resolution;

uniform sampler2D groundMap;
uniform sampler2D waterHeightMap;

const float width = 0.37;
const float height = 1.;

float noise3D(vec3 p) {
    return fract(sin(dot(p, vec3(12.9898, 78.233, 128.852))) * 43758.5453) * 2.0 - 1.0;
}

float simplex3D(vec3 p) {
    float f3 = 1.0 / 3.0;
    float s = (p.x + p.y + p.z) * f3;
    int i = int(floor(p.x + s));
    int j = int(floor(p.y + s));
    int k = int(floor(p.z + s));

    float g3 = 1.0 / 6.0;
    float t = float((i + j + k)) * g3;
    float x0 = float(i) - t;
    float y0 = float(j) - t;
    float z0 = float(k) - t;
    x0 = p.x - x0;
    y0 = p.y - y0;
    z0 = p.z - z0;

    int i1, j1, k1;
    int i2, j2, k2;

    if(x0 >= y0) {
        if(y0 >= z0) {
            i1 = 1;
            j1 = 0;
            k1 = 0;
            i2 = 1;
            j2 = 1;
            k2 = 0;
        } else if(x0 >= z0) {
            i1 = 1;
            j1 = 0;
            k1 = 0;
            i2 = 1;
            j2 = 0;
            k2 = 1;
        } else {
            i1 = 0;
            j1 = 0;
            k1 = 1;
            i2 = 1;
            j2 = 0;
            k2 = 1;
        }
    } else {
        if(y0 < z0) {
            i1 = 0;
            j1 = 0;
            k1 = 1;
            i2 = 0;
            j2 = 1;
            k2 = 1;
        } else if(x0 < z0) {
            i1 = 0;
            j1 = 1;
            k1 = 0;
            i2 = 0;
            j2 = 1;
            k2 = 1;
        } else {
            i1 = 0;
            j1 = 1;
            k1 = 0;
            i2 = 1;
            j2 = 1;
            k2 = 0;
        }
    }

    float x1 = x0 - float(i1) + g3;
    float y1 = y0 - float(j1) + g3;
    float z1 = z0 - float(k1) + g3;
    float x2 = x0 - float(i2) + 2.0 * g3;
    float y2 = y0 - float(j2) + 2.0 * g3;
    float z2 = z0 - float(k2) + 2.0 * g3;
    float x3 = x0 - 1.0 + 3.0 * g3;
    float y3 = y0 - 1.0 + 3.0 * g3;
    float z3 = z0 - 1.0 + 3.0 * g3;

    vec3 ijk0 = vec3(i, j, k);
    vec3 ijk1 = vec3(i + i1, j + j1, k + k1);
    vec3 ijk2 = vec3(i + i2, j + j2, k + k2);
    vec3 ijk3 = vec3(i + 1, j + 1, k + 1);

    vec3 gr0 = normalize(vec3(noise3D(ijk0), noise3D(ijk0 * 2.01), noise3D(ijk0 * 2.02)));
    vec3 gr1 = normalize(vec3(noise3D(ijk1), noise3D(ijk1 * 2.01), noise3D(ijk1 * 2.02)));
    vec3 gr2 = normalize(vec3(noise3D(ijk2), noise3D(ijk2 * 2.01), noise3D(ijk2 * 2.02)));
    vec3 gr3 = normalize(vec3(noise3D(ijk3), noise3D(ijk3 * 2.01), noise3D(ijk3 * 2.02)));

    float n0 = 0.0;
    float n1 = 0.0;
    float n2 = 0.0;
    float n3 = 0.0;

    float t0 = 0.5 - x0 * x0 - y0 * y0 - z0 * z0;
    if(t0 >= 0.0) {
        t0 *= t0;
        n0 = t0 * t0 * dot(gr0, vec3(x0, y0, z0));
    }
    float t1 = 0.5 - x1 * x1 - y1 * y1 - z1 * z1;
    if(t1 >= 0.0) {
        t1 *= t1;
        n1 = t1 * t1 * dot(gr1, vec3(x1, y1, z1));
    }
    float t2 = 0.5 - x2 * x2 - y2 * y2 - z2 * z2;
    if(t2 >= 0.0) {
        t2 *= t2;
        n2 = t2 * t2 * dot(gr2, vec3(x2, y2, z2));
    }
    float t3 = 0.5 - x3 * x3 - y3 * y3 - z3 * z3;
    if(t3 >= 0.0) {
        t3 *= t3;
        n3 = t3 * t3 * dot(gr3, vec3(x3, y3, z3));
    }
    return 96.0 * (n0 + n1 + n2 + n3);
}

float fbm(vec3 p) {
    float f;
    f = 0.50000 * simplex3D(p);
    p = p * 2.01;
    f += 0.25000 * simplex3D(p);
    p = p * 2.02;
    f += 0.12500 * simplex3D(p);
    p = p * 2.03;
    f += 0.06250 * simplex3D(p);
    p = p * 2.04;
    f += 0.03125 * simplex3D(p);
    return f * 0.5 + 0.5;
}

vec2 B(vec2 uv) {
    vec2 flowField = texture(waterHeightMap, uv).gb;
    flowField = (flowField - 0.5) * 2.0;
    // if(uv.x > 0.5)
    //     flowField = vec2(0., -1.);
    // else
    //     flowField = vec2(0., 1.);

    vec2 uv1 = vec2(uv.x + flowField.x * iTime, uv.y + flowField.y * iTime);
    vec3 rain = texture(groundMap, uv1).rgb / 8.;

    vec2 uv2 = uv.xy - rain.xy / 4.;
    uv2.x += flowField.x * iTime * 0.5;
    uv2.y += flowField.y * iTime * 0.4;
    uv2 *= 40.;

    return vec2(fbm(vec3(uv2, iTime)), fbm(vec3(uv2 + 123., iTime + 123.)));
}

void main() {
    vec2 uv = gl_FragCoord.xy / resolution;
    vec4 fondo = texture(groundMap, uv - B(uv));
    if(texture(waterHeightMap, uv).a <= 0.01 || texture(waterHeightMap, uv).r <= 0.01)
        discard;
    out_FragColor = fondo;

    // out_FragColor = texture(groundMap, uv);
}