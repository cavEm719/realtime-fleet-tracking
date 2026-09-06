# Sourced by the MySQL entrypoint while the replica initializes its own empty data directory.
# The replica already created the same database and users itself, so it starts replicating from
# the primary's current GTID set; later schema migrations and all data arrive by replication.
until mysql -h mysql -u repl -prepl -e 'SELECT 1' >/dev/null 2>&1; do sleep 1; done
primary_gtids=$(mysql -h mysql -u repl -prepl -N -e 'SELECT @@GLOBAL.gtid_executed' | tr -d '\n')
docker_process_sql <<SQL
CREATE USER 'fleet_ai'@'%' IDENTIFIED BY 'fleet_ai';
GRANT SELECT ON fleet.* TO 'fleet_ai'@'%';
RESET MASTER;
SET GLOBAL gtid_purged = '${primary_gtids}';
CHANGE REPLICATION SOURCE TO SOURCE_HOST = 'mysql', SOURCE_USER = 'repl', SOURCE_PASSWORD = 'repl',
    SOURCE_AUTO_POSITION = 1, GET_SOURCE_PUBLIC_KEY = 1;
SET PERSIST super_read_only = ON;
SQL
