#! /usr/bin/env python
"""Basic Model Interface implementation for ANUGA."""
import csv

import anuga
import numpy as np
import scipy
from scipy.interpolate import interp1d
from scipy.spatial import cKDTree
import yaml

from basic_modeling_interface import Bmi
from anuga_solver import AnugaSolver


class BmiAnuga(Bmi):

    def __init__(self):
        """Create a BmiAnuga model that is ready for initialization."""
        self._anuga = None
        self._time = 0.
        self._values = {}
        self._var_units = {}
        self._grids = {}
        self._grid_type = {}
        self.regions = {}
        self.elevation = {}
        self.rate_operator = {}
        self.area_map = {}
        self.rain = {}

    def initialize_ANUGA(self, filename='anuga.yaml'):
        """Initialize the ANUGA model.

        Parameters
        ----------
        filename : str, optional
            Path to name of input file.
        """

        with open(filename, 'r') as file_obj:
            params = yaml.load(file_obj, Loader=yaml.FullLoader)

        default_params = {
            'domain_type': 'square',
            'shape': (10., 5.),
            'size': (10., 5.),
            'friction': 0.,
            'boundary_filename': '',
            'point_and_vertices': [],
            'pipe_Index_Cord': '',
            'rainfall': '',
            'elevation_filename': '',
            'elevation_profile': 'shallow linear ramp',
            'output_dir': '',
            'output_filename': 'anuga_output',
            'output_timestep': 10,
            'save_timestep': 0,
            'boundary_tags': {
                'left': [],
                'right': [],
                'top': [],
                'bottom': [],
            },
            'boundary_conditions': {
                'left': 'Reflective',
                'right': ['Dirichlet', 5, 0, 0],
                'top': 'Reflective',
                'bottom': 'Reflective',
            },
            'stored_quantities': {
                'stage': 2,
                'xmomentum': 2,
                'ymomentum': 2,
                'height': 2,
                'elevation': 1,
                'land_friction': 1,
            },
            'maximum_triangle_area': 10,
            'regionPtArea_filename': '',
            'initial_flow_depth': 0,
            'interior_polygon_filename': '',
            'interior_polygon_triangle_area': 0.0,
            'toggle_sediment_transport': False,
            'inflow_sediment_concentration': 0.0,
            'initial_sediment_concentration': 0.0,
            'toggle_vegetation_drag': False,
            'vegetation_stem_diameter': 0.0,
            'vegetation_stem_spacing': 0.0,
            'Mannings_n_parameter': 0.0,
            'landuse': None,
            'infiltration': None,
        }

        for key, value in default_params.items():
            params[key] = params.get(key, value)

        for key, value in params.items():
            if (value is None) or (value == 'None'):
                params[key] = default_params[key]

        assert (set(params['boundary_conditions'].keys()) ==
                set(params['boundary_tags'].keys())), (
                "The boundary tag names don't match the boundary "
                "condition names. Check that the two dictionaries use "
                "the same boundary names.")

        self._anuga = AnugaSolver(params)
        self.create_rainfall()
        self.create_pipe_dict()
        self.create_elevation_dict()
        self.create_rate_dict()
        self.create_area_dict()
        self._anuga.initialize_infiltration_operator()

    def update_test(self):
        for _ in self._anuga.domain.evolve(
                yieldstep=self._anuga._time_step, finaltime=self._time):
            print(self._anuga.domain.timestepping_statistics())

    def update_ANUGA(self):
        """Advance model by one time step."""
        self._time += self.get_time_step_ANUGA()
        self._anuga._time = self._time
        self._anuga.update()

    def update_frac_ANUGA(self, time_frac):
        """Update model by a fraction of a time step.

        Parameters
        ----------
        time_frac : float
            Fraction of a time step.
        """
        time_step = self.get_time_step_ANUGA()
        self._anuga.time_step = time_frac * time_step
        self.update_ANUGA()
        self._anuga.time_step = time_step

    def update_until(self, then):
        """Update model until a particular time."""
        n_steps = (
            then - self.get_current_time_ANUGA()) / self.get_time_step_ANUGA()
        for _ in range(int(n_steps)):
            self.update_ANUGA()
        if (n_steps - int(n_steps)) > 0.0:
            self.update_frac_ANUGA(n_steps - int(n_steps))

    def finalize_ANUGA(self):
        """Finalize model."""
        self._anuga = None

    def create_rainfall(self, delimiter=",", polygon=None):
        rain_timeseries = scipy.genfromtxt(
            self._anuga._rainfall, delimiter=delimiter, skip_header=1)

        rain_timeseries[:, 1] = rain_timeseries[:, 1] / (3600. * 1000.)

        assert rain_timeseries[:, 1].min() >= 0., 'Negative rainfall input'

        if rain_timeseries[:, 1].max() >= 0.:
            rain_interp = interp1d(
                rain_timeseries[:, 0], rain_timeseries[:, 1],
                kind='linear',
                bounds_error=False,
                fill_value=(rain_timeseries[0, 1], rain_timeseries[-1, 1]))

            def myrain(t):
                return max(float(rain_interp(t)), 0.0)

            anuga.operators.rate_operators.Rate_operator(
                self._anuga.domain,
                rate=myrain,
                polygon=polygon,
                label=self._anuga._rainfall)

    def create_elevation_dict(self):
        for key, value in self.regions.items():
            values = value.domain.quantities['elevation'].centroid_values[
                value.indices]
            self.elevation[key] = float(np.asarray(values).reshape(-1)[0])

    def create_pipe_dict(self, radius=100, progress_step=None):
        domain = self._anuga.domain
        centroid_coords = domain.get_centroid_coordinates(absolute=True)

        if centroid_coords.size == 0:
            self.regions = {}
            return

        search_tree = cKDTree(centroid_coords)

        pipe_coords = []
        pipe_names = []

        with open(self._anuga._pipe_Index_Cord, newline='') as fp:
            reader = csv.reader(fp)
            for row in reader:
                if not row:
                    continue

                head = row[0].strip()
                if not head or head.startswith('#'):
                    continue

                try:
                    x = float(head)
                    y = float(row[1])
                except (ValueError, IndexError):
                    continue

                if len(row) > 2 and row[2].strip():
                    name = row[2].strip()
                else:
                    name = 'pipe_%s' % len(pipe_names)

                pipe_coords.append((x, y))
                pipe_names.append(name)

        if not pipe_coords:
            self.regions = {}
            return

        query_points = np.asarray(pipe_coords)
        distances, indices = search_tree.query(query_points)

        distances = np.atleast_1d(distances)
        indices = np.atleast_1d(indices).astype(int)

        regions = {}
        radius_limit = float(radius) if radius is not None else None

        total = len(pipe_names)
        if progress_step is None:
            progress_step = max(1, total // 10) if total > 10 else total
        elif progress_step <= 0:
            progress_step = None
        else:
            progress_step = int(progress_step)

        if progress_step:
            print('[create_pipe_dict] building %s pipe regions, progress every %s'
                  % (total, progress_step))

        for processed_count, (name, centroid_idx, distance) in enumerate(
                zip(pipe_names, indices, distances), start=1):
            if (radius_limit is not None and radius_limit > 0.0 and
                    distance > radius_limit):
                # Fall back to the nearest centroid on sparse meshes.
                pass

            region = anuga.Region(domain, indices=[centroid_idx])
            regions[name] = region

            if progress_step and (
                    processed_count % progress_step == 0 or
                    processed_count == total):
                print('[create_pipe_dict] processed %s/%s pipe regions'
                      % (processed_count, total))

        self.regions = regions

    def create_rate_dict(self):
        # Keep one SWMM exchange rate operator per mapped pipe/node region.
        for key, value in self.regions.items():
            rate = anuga.Rate_operator(
                self._anuga.domain, rate=0, indices=value.indices)
            self.rate_operator[key] = rate

    def create_area_dict(self):
        for key, value in self.regions.items():
            areas = self._anuga.domain.areas[value.indices]
            self.area_map[key] = float(np.asarray(areas).sum())

    def get_start_time_ANUGA(self):
        """Start time of model."""
        return 0.

    def get_end_time_ANUGA(self):
        """End time of model."""
        return np.finfo('d').max

    def get_current_time_ANUGA(self):
        """Current time of model."""
        return self._time

    def get_time_step_ANUGA(self):
        """Time step of model."""
        return self._anuga.time_step

    def set_stage_ANUGA(self, value=0, name=None,
                        location="centroids", indices=None):
        if indices is None:
            indices = self.regions[name].indices
        self._anuga.domain.set_quantity(
            "stage",
            location=location,
            indices=indices,
            numeric=self.elevation[name] + value)

    def set_rate_ANUGA(self, value=0, name=None,
                       location="centroids", indices=None):
        self.rate_operator[name].set_rate(value)

    def get_stage_ANUGA(self, name=None, location="centroids", indices=None):
        scalar_region = False
        if indices is None:
            indices = self.regions[name].indices
            scalar_region = name is not None
        values = self._anuga.domain.get_quantity("stage").get_values(
            location=location, indices=indices)
        if scalar_region and np.size(values) == 1:
            return float(np.asarray(values).reshape(-1)[0])
        return values

    def get_elevation_ANUGA(self, name=None):
        return self.elevation[name]

    def get_rate_operator(self, name=None):
        return self.rate_operator[name]

    def get_area_ANUGA(self, name=None, indices=None, location="centroids"):
        scalar_region = False
        if indices is None:
            indices = self.regions[name].indices
            scalar_region = name is not None
        values = self._anuga.domain.areas[indices]
        if scalar_region:
            return float(np.asarray(values).sum())
        return values

    def get_infiltration_rate_ANUGA(self, location="centroids", indices=None):
        return self._anuga.domain.get_quantity(
            "infiltration_rate").get_values(
                location=location, indices=indices)

    def get_cumulative_infiltration_ANUGA(self, location="centroids",
                                          indices=None):
        return self._anuga.domain.get_quantity(
            "cumulative_infiltration").get_values(
                location=location, indices=indices)
