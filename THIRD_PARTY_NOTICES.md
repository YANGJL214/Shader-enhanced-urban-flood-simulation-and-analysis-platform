# Third-party notices

This repository includes or depends on third-party software. The project license does not replace the licenses of these components.

## Redistributed asset

### CesiumJS 1.116

Location: `frontend/public/Cesium`

The distributed `Cesium.js` header identifies CesiumJS version 1.116, copyright Cesium Contributors, and licensing under the Apache License 2.0. It also states that portions are licensed separately. Preserve the upstream notices when redistributing these files. The applicable notice text is embedded in `frontend/public/Cesium/Cesium.js`.

Project website: https://cesium.com/platform/cesiumjs/

License: https://www.apache.org/licenses/LICENSE-2.0

## Package-managed dependencies

- Frontend dependencies and resolved versions: `frontend/package.json` and `frontend/package-lock.json`.
- Backend dependencies and versions: `backend/pom.xml`.
- Python runtime dependencies used for development: `runtime/requirements.txt`.
- Infrastructure images: `infra/docker-compose.yml`.

Before publishing an archival release, generate a dependency license report and review all transitive licenses.
