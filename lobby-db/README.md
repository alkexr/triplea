# lobby-db


- locallobby database: [Dockerfile](https://github.com/triplea-game/triplea/blob/master/lobby-db/Dockerfile) 
- [migrations files](https://github.com/triplea-game/triplea/tree/master/lobby-db/src/main/resources/db/migration).
is where we check-in SQL commands to update database. Any new files are run automatically as part of lobby deployment.

 
## Dev Setup

To launch a local dabase on Docker, run: `launch_db`

### Prerequisites
- Docker
- `psql` (postgres-client) command 

### Typical Usage

After the first time you can just run docker to launch faster:
```
run_docker
```

To connect to your local database:
```
./connect_to_db
```

To recreate DB data:
```
$ ./drop_db
      ## Re-run fly migrations.
      ## Includes any new in-development files.
$ ./run_flyway
```


## Connection Configuration

A lobby server running on the same host as the lobby database container may connect to the database using the following properties:

Property | Value | Notes
:-- | :-- | :--
User | `postgres` |
Password | _&lt;any&gt;_ | The lobby database is configured with authentication disabled, thus any password may be used.
Host | `localhost` |
Port | `5432` |

