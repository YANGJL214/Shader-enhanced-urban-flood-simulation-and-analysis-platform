# Shader-enhanced Urban Flood Simulation

An interactive research prototype for configuring, running, and visualizing coupled surface-water and sewer-network simulations. The application combines an ANUGA-based surface model, SWMM network results, a Spring Boot orchestration service, and Cesium/WebGL rendering.


## Main capabilities

- Import a DEM, optional land-use raster, and node/link Shapefiles.
- Build regular or adaptive triangular meshes for a study region.
- Configure rainfall-driven urban-flood simulations.
- Run the model through a Spring Boot service and receive progress through MQTT.
- Explore surface-water depth and sewer-network variables in a Cesium scene.
- Save and reload simulation histories.

## Repository layout

```text
backend/    Spring Boot API and model orchestration
database/   PostGIS schema initialization
data/       Generated regions and simulation workspaces (Git-ignored)
docs/       Reproducibility and verification notes
frontend/   Vue 3, Cesium, and WebGL visualization client
infra/      Docker Compose services for PostGIS and EMQX
runtime/    ANUGA adapters, model templates, and Windows launcher
scripts/    Windows helper scripts
```

## System requirements

The current model launcher is Windows-specific.

- Windows 10 or 11
- Docker Desktop with Docker Compose
- Java Development Kit 8
- Maven 3.8 or newer, or the Maven bundled with IntelliJ IDEA
- Node.js 18 or newer and npm
- Python 3.9 environment at `runtime/anuga_env`

The Python package versions used during development are recorded in [`runtime/requirements.txt`](runtime/requirements.txt). A local environment can be created with Conda:

```powershell
conda create --prefix .\runtime\anuga_env python=3.9 -y
conda run --prefix .\runtime\anuga_env python -m pip install -r .\runtime\requirements.txt
```

Run the environment check from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-environment.ps1
```

## Quick start

### 1. Start PostGIS and EMQX

Start Docker Desktop, then run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-infra.ps1
```

The development services bind only to localhost:

| Service | Address |
|---|---|
| PostgreSQL/PostGIS | `127.0.0.1:5432`, database `urbanflood` |
| MQTT | `tcp://127.0.0.1:1883` |
| MQTT over WebSocket | `ws://127.0.0.1:8083/mqtt` |
| EMQX dashboard | `http://127.0.0.1:18083` |

The example passwords in `infra/.env.example` are for local development only. Copy that file to `infra/.env` and replace them before using the services outside an isolated workstation.

### 2. Start the backend

Open `backend/pom.xml` in IntelliJ IDEA, select JDK 8, and run:

```text
cn.com.medoxcq.UrbanFloodApplication
```

Set the working directory to `backend`. The API listens on `http://127.0.0.1:8080`; its health endpoint is `http://127.0.0.1:8080/actuator/health`.

Command-line alternative:

```powershell
cd backend
mvn spring-boot:run
```

### 3. Start the frontend

```powershell
cd frontend
Copy-Item .env.example .env.local
npm install
npm run dev
```

Open `http://127.0.0.1:5173`. Vite forwards `/api` requests to the backend.

## Typical workflow

To run the interface workflow with compatible study inputs:

1. Open **Region Management**, add a region, and upload the DEM and all required Shapefile sidecars (`.shp`, `.dbf`, `.shx`, and `.prj`).
2. Optionally upload the land-use raster, configure field mappings, and select regular or adaptive meshing.
3. Wait until the region status is **Completed**.
4. Open **Model Parameters**, select the initialized region, upload a rainfall CSV, and set the simulation duration.
5. Select **Run**. Progress appears in the toolbar and results are stored automatically.
6. Use **Simulation History** to reload a completed result.

Generated mesh files, model inputs, logs, and results are written under `data/regions` and `data/workspaces`; both locations are excluded from version control.

## Configuration

Frontend settings are documented in `frontend/.env.example`.

| Variable | Purpose |
|---|---|
| `VITE_BACKEND_URL` | Vite development proxy target |
| `VITE_MQTT_URL` | Browser MQTT WebSocket endpoint |
| `VITE_BASEMAP_URL` | Optional XYZ imagery template |
| `VITE_TIANDITU_TOKEN` | Optional TianDiTu browser token |
| `VITE_CESIUM_ION_TOKEN` | Optional Cesium ion access token |
| `VITE_DEM` | Optional Cesium terrain service URL |
| `VITE_BUILDING_TILESET_URL` | Optional 3D building tileset |

Backend paths and service endpoints can be overridden with environment variables:

| Variable | Default |
|---|---|
| `URBANFLOOD_DB_URL` | `jdbc:postgresql://127.0.0.1:5432/urbanflood` |
| `URBANFLOOD_DB_USERNAME` | `urbanflood` |
| `URBANFLOOD_DB_PASSWORD` | `urbanflood_dev` |
| `URBANFLOOD_MQTT_URI` | `tcp://127.0.0.1:1883` |
| `URBANFLOOD_RUNTIME_ROOT` | `../runtime` |
| `URBANFLOOD_WORKSPACE_ROOT` | `../data/workspaces` |
| `URBANFLOOD_REGION_ROOT` | `../data/regions` |
| `URBANFLOOD_PYTHON` | `../runtime/anuga_env/python.exe` |

Map-provider tokens and private service URLs belong in `frontend/.env.local`, which is Git-ignored.

## Inputs and outputs

Primary inputs are ESRI ASCII rasters with projection files, complete node and link Shapefiles, a rainfall CSV, mesh settings, and hydraulic parameters entered in the region form. The backend derives model YAML and SWMM INP files for each workspace.

Primary outputs include ANUGA SWW results, water-depth image frames and metadata for WebGL rendering, time-indexed and object-indexed SWMM JSON, execution logs, and database history records.

## Reproducibility scope

This repository supports inspection and execution of the web application, data-preparation workflow, Python adapters, and visualization code. Important current limitations are documented in [`docs/REPRODUCIBILITY.md`](docs/REPRODUCIBILITY.md):

- The bundled Python environment is not committed; it must be recreated from `runtime/requirements.txt`.
- Paper-level experiment manifests, evaluation scripts, and reference outputs are not yet included.
- External imagery, terrain, and building services are optional and are not archived in this repository.

These limitations must be resolved or explicitly justified before the archival paper release.

## Verification

The maintained checks and their current scope are listed in [`docs/REPRODUCIBILITY.md`](docs/REPRODUCIBILITY.md). The normal local checks are:

```powershell
docker compose -f .\infra\docker-compose.yml config
cd backend; mvn test
cd ..\frontend; npm run lint; npm run build
cd ..; powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-python.ps1
```

## Third-party software

CesiumJS 1.116 is redistributed under `frontend/public/Cesium`. Its header identifies the Apache License 2.0 and separately licensed portions. Other JavaScript, Java, and Python dependencies are resolved from their package manifests. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## License

Copyright (C) 2026 the authors of the associated *Computers & Geosciences* article.

This project is licensed under the [GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`). Third-party components remain subject to their respective licenses; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Stopping local services

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-infra.ps1
```

Docker volumes are retained so local PostGIS and EMQX state survives a restart.
