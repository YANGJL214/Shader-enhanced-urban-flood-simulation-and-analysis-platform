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
    vec4 t = vec4(EMPTY);
    if(iFrame == 0)//fill 2D space with balls or EMPTY
    {
        t = rand(gl_FragCoord.xy) * vec4(2., 2., .02, .02) - vec4(1., 1., .01, .01);
        if(fract(sin(dot(gl_FragCoord.xy, vec2(12.989, 78.233))) * 43758.5453) < .9) {
            t = vec4(EMPTY);
        }
    }
    vec2 r = 1. / iResolution.xy;
    vec2 a = floor(gl_FragCoord.xy * .5) * 2. - 2.;
    float id = dot(floor(fract(gl_FragCoord.xy * .5) * 2.), vec2(1., 2.)) + 1.;
    float id2 = 0.;
    for(float i = .5; i < 6.; ++i) {
        for(float j = .5; j < 6.; ++j) {
            vec2 m = vec2(j, i);
            vec4 t2 = texture(iChannel0, (a + m) * r).xyzw;
            if(t2.x == EMPTY) {
                continue;
            }
            t2.xy += 2. * (floor(m * .5) - 1.);
            if(t2.x < 1. && t2.x >= -1. &&
                t2.y < 1. && t2.y >= -1.) {
                ++id2;
            }
            if(abs(id2 - id) < .1)//this means if id2 == id break
            {
                out_FragColor = t2;
                return;
            }
        }
    }
    out_FragColor = t;
}