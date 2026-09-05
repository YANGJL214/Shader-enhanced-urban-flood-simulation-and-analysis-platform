precision highp float;
in vec3 position;
in vec3 positionHigh;
in vec3 positionLow;
in vec3 normal;
in vec2 uv;
in float pipeTypes;
in float attributeIndex;
in float pipeDir;
in vec3 boxMax;
in vec3 boxMin;

out vec2 f_uv;
out float f_pipeTypes;
flat out float f_attributeIndex;
out float f_pipeDir;
flat out float f_depth;
flat out vec3 f_boxMax;
flat out vec3 f_boxMin;
out vec3 v_modelPos;
out vec3 v_cameraModelPos;
out vec3 v_viewDirModel;

uniform sampler2D u_attrMap;
void main() {
  f_uv = uv;
  f_pipeTypes = pipeTypes;
  f_attributeIndex = attributeIndex;
  f_pipeDir = pipeDir;
  f_depth = pipeDir;
  f_boxMax = boxMax;
  f_boxMin = boxMin;

  vec3 positionDecode = positionHigh + positionLow;

  v_modelPos = positionDecode;

  v_cameraModelPos = czm_encodedCameraPositionMCHigh + czm_encodedCameraPositionMCLow;
  v_viewDirModel = v_modelPos - v_cameraModelPos;

  gl_Position = czm_projection * czm_view * czm_model * vec4(positionDecode, 1.0);
}