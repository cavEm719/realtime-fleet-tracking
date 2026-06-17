-- Approximate harbor/terminal anchors; demo circles, not official port boundaries.
-- Enter/exit radii are meters. The wider exit circle prevents boundary jitter.
INSERT INTO fence (id, tenant_id, name, center_lat, center_lon, enter_radius_m, exit_radius_m, version) VALUES
    (1, 1, 'Los Angeles', 33.73000, -118.26250, 2000, 3000, 1),
    (2, 2, 'Long Beach', 33.75400, -118.21600, 2000, 3000, 1),
    (3, 3, 'Oakland', 37.79500, -122.30500, 2000, 3000, 1),
    (4, 1, 'Seattle', 47.58000, -122.35000, 2000, 3000, 1),
    (5, 2, 'Tacoma', 47.27000, -122.41000, 2000, 3000, 1),
    (6, 3, 'Houston', 29.73000, -95.02000, 3000, 4500, 1),
    (7, 1, 'New York / New Jersey (Port Newark)', 40.68250, -74.14861, 2500, 4000, 1),
    (8, 2, 'Savannah', 32.12000, -81.15000, 2500, 4000, 1);
