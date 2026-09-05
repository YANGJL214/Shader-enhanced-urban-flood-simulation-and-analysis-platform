# Reproducibility and verification

This document distinguishes checks that can be run from the repository from scientific reproduction materials that are still missing.

## Included materials

- Source code for the Vue/Cesium client and Spring Boot orchestration service.
- Python source for the ANUGA adapter, infiltration operator, adaptive-mesh generator, and SWMM result extractor.
- Docker Compose definitions for PostGIS and EMQX.
- Database initialization SQL.
- A Windows executable used to launch the coupled model workflow.

## Automated checks

Run these commands from PowerShell after installing the documented prerequisites.

| Area | Command | What it establishes |
|---|---|---|
| Infrastructure | `docker compose -f .\infra\docker-compose.yml config` | Compose syntax and variable expansion |
| Backend | `cd backend; mvn test` | Java compilation and available unit tests |
| Frontend lint | `cd frontend; npm run lint` | Vue and JavaScript static checks |
| Frontend build | `cd frontend; npm run build` | Production bundling and asset resolution |
| Python source and CLIs | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-python.ps1` | Project-source parsing and template entry-point imports |

## Verification snapshot

The following checks were completed on 2 September 2026:

- Docker Compose configuration validation passed for PostGIS and EMQX.
- Maven compiled 47 backend source files successfully; the repository currently contains no backend test sources.
- Frontend linting completed with no errors or warnings.
- The frontend production build completed successfully. Sass reported legacy-API deprecation notices, and Vite reported a large bundle-size advisory.
- Python syntax checks passed for the five maintained project modules and both command-line entry points.
- Browser smoke tests opened the Chinese and English interfaces and the corresponding single-column model-parameter dialog. Simulation-result saving is fixed to enabled and is not exposed as a user control.

The browser smoke test did not execute a model run because the backend and infrastructure services were not started. An unavailable configured terrain endpoint produced Cesium tile-request warnings but did not prevent the interface from loading.

## End-to-end check

A full application check requires all four layers to be running:

1. Start PostGIS and EMQX.
2. Start the Spring Boot backend with `backend` as its working directory.
3. Start the Vite development server.
4. Initialize a region with compatible DEM, land-use, sewer-network, and rainfall inputs.
5. Run a rainfall scenario and confirm that MQTT progress reaches completion.
6. Confirm that surface-water and sewer-network results render and can be reloaded from history.

Record the input filenames, parameter values, software release tag, and generated workspace identifier when using this check for a publication artifact.

## Current gaps

### Paper-level reproduction package

Input data are not distributed in this repository. A paper-level reproduction package also requires reference outputs, evaluation scripts, plotting scripts, and expected numerical tolerances.

For the manuscript release, add a versioned experiment manifest containing:

- input checksums and coordinate reference systems;
- all model and mesh parameters;
- expected output files and summary statistics;
- commands or scripts for regenerating paper figures and tables;
- acceptable numerical tolerances and the hardware/software environment.

### External map services

Imagery, terrain, and building tilesets are supplied by configurable external services. They affect visual context but are not model inputs. Archive screenshots or provider-independent visualization exports if the manuscript relies on a specific visual appearance.
