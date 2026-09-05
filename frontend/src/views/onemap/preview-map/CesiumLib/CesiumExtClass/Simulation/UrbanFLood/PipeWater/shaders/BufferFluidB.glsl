#define ZOOM .5        //camera zoom, should be < .5
#define EMPTY 1024.     //there is no ball inside this pixel
#define FTOV .125 		//force to velocity
#define VTOP (FTOV*2.)  //velocity to position
#define FRC1 4.         //repulsion collision force
#define FRC2 1.         //inelastic collision force

uniform float iTime;
uniform float iTimeDelta;
uniform int iFrame;
uniform vec2 iResolution;
uniform sampler2D iChannel0;
uniform sampler2D waterHeightMap;

vec4 rand(vec2 u) {
    return fract(sin(vec4(dot(u, vec2(23.123, 87.987)), dot(u, vec2(34.234, 96.876)), dot(u, vec2(45.345, 15.765)), dot(u, vec2(56.456, 24.654)))) * 45678.7654);
}
void main() {
    vec2 r = 1. / iResolution.xy;
    vec2 u = gl_FragCoord.xy;
    vec4 t = texture(iChannel0, u * r).xyzw;

    vec4 waterInfo = texture(waterHeightMap, gl_FragCoord.xy * r);
    if(waterInfo.r < 0.01)
        t = vec4(EMPTY);
    if(t.x == EMPTY && waterInfo.r > 0.015)
        t = rand(gl_FragCoord.xy) * vec4(2., 2., .02, .02) - vec4(1., 1., .01, .01);

    u = floor(gl_FragCoord.xy * .5) * 2. - 2.;
    vec2 v = vec2(0.);
    if(iFrame == 0)//fill 2D space with balls or EMPTY
    {
        t = rand(gl_FragCoord.xy) * vec4(2., 2., .02, .02) - vec4(1., 1., .01, .01);
        if(fract(sin(dot(gl_FragCoord.xy, vec2(12.989, 78.233))) * 43758.5453) < .9) {
            t = vec4(EMPTY);
        }
    }
    for(float i = .5; i < 6.; ++i) {
        for(float j = .5; j < 6.; ++j) {
            vec4 m = vec4(j, i, 0., 0.);
            vec4 t2 = texture(iChannel0, (u + m.xy) * r).xyzw;
            if(t2.x == EMPTY) {
                continue;
            }
            vec4 p = t - t2 + vec4(2., 2., 0., 0.) - 2. * floor(m * .5);
            vec2 d = p.xy;
            float l = length(d);
            if(l > 2. || l < .0001) {
                continue;
            }         //no collision
            d /= l;                    //direction force of collision normalized
            float c = (2. - l) * FRC1;     //repulsion force of collision 
            float e = dot(d, p.zw) * FRC2;//inelastic force of collision 
            v += d * (c - e);

            vec4 waterInfo = texture(waterHeightMap, (u + m.xy) * r);
            vec2 gb = waterInfo.gb;
            vec2 vel = gb * 2.0 - 1.0;
            vel.y = -vel.y;

            v *= .1;
            v += vel * 1.;

            // if(waterInfo.a < 0.01)
            //     t = vec4(EMPTY);
        }
    }
    t.zw += v * FTOV * FTOV;
    t.xy += t.zw * VTOP;
    out_FragColor = t;
}