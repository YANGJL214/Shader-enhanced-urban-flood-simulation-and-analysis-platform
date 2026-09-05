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

vec4 rand(vec2 u) {
    return fract(sin(vec4(dot(u, vec2(23.123, 87.987)), dot(u, vec2(34.234, 96.876)), dot(u, vec2(45.345, 15.765)), dot(u, vec2(56.456, 24.654)))) * 45678.7654);
}
void main() {
    vec2 r = 1. / iResolution.xy;
    vec2 u = gl_FragCoord.xy * ZOOM;
    vec4 t = vec4(fract(u) * 2. - 1., 0., 0.);
    vec4 s = step(vec4(0.), t) * vec4(1., 1., 0., 0.) - vec4(1., 1., 0., 0.);
    u = (floor(u) + s.xy) * 2.;
    s *= 2.;
    vec4 v = vec4(.6);
    for(float i = .5; i < 4.; ++i) {
        for(float j = .5; j < 4.; ++j) {
            vec4 m = vec4(j, i, 0., 0.);
            vec4 t2 = texture(iChannel0, (u + m.xy) * r).xyzw;
            vec4 p = t - t2 - s - 2. * floor(m * .5);
            if(t2.x != EMPTY && dot(p.xy, p.xy) < 1.) {
                v = t2;
            }
        }
    }
    //color the ball depending on its velocity
    out_FragColor = .5 + .5 * cos(6.28 * (.5 + length(v.zw) + vec4(.0, .1, .2, .0)));

    float speed = length(v.zw);
    float normalizedSpeed = clamp(speed * 0.8, 0.0, 1.0);

    vec3 darkBlue = vec3(0.0706, 0.4196, 0.6824);
    vec3 lightBlue = vec3(0.1333, 0.6353, 0.7647);

    vec3 color = mix(darkBlue, lightBlue, normalizedSpeed);
    out_FragColor = vec4(color, 1.0);

    if(length(v - vec4(.6)) < 0.1)
        out_FragColor = vec4(0.);
}