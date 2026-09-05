CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS flood_region (
    id                    varchar(64) PRIMARY KEY,
    region_name           varchar(255) NOT NULL,
    description           text,
    data_path             text,
    tin_file_path         text,
    dem_file_path         text,
    node_shp_path         text,
    link_shp_path         text,
    landuse_file_path     text,
    config_params         text,
    init_status           integer NOT NULL DEFAULT 0,
    error_message         text,
    create_time           timestamp,
    update_time           timestamp,
    creator               varchar(128),
    updater               varchar(128),
    deleted               varchar(1) NOT NULL DEFAULT '0'
);

CREATE INDEX IF NOT EXISTS idx_flood_region_owner
    ON flood_region (creator, init_status)
    WHERE deleted = '0';

CREATE TABLE IF NOT EXISTS model_simulation_history (
    id                    varchar(64) PRIMARY KEY,
    simulation_name       varchar(255),
    model_name            integer,
    state                 integer,
    simulation_params     text,
    process_id            varchar(64),
    save_data             boolean NOT NULL DEFAULT false,
    workspace_path        text,
    create_time           timestamp,
    update_time           timestamp,
    create_by             varchar(128),
    update_by             varchar(128),
    del_flag              varchar(1) NOT NULL DEFAULT '0'
);

CREATE INDEX IF NOT EXISTS idx_simulation_history_lookup
    ON model_simulation_history (model_name, state, create_time DESC)
    WHERE del_flag = '0';
