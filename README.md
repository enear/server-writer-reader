server-write-reader
=========================

# running locally in the same host

> $ sbt "run Server"

> $ sbt "run Reader"

> $ sbt "run Writer"

# running with docker

## create image
> $ sbt docker:publishLocal

## run containers with docker-compose
> $ docker-compose up -d

JMX is exposed in ports 9991, 9992, and 9993. Graphana is exposed in port 80 

# graphana dashboard 

## configuration
* browse to localhost:80
* login with the default username (admin) and password (admin)
* configure a new datasource
  * Data Sources
  * Add new
  * Name: "default"; Check "defaul checkbox"; Url: http://localhost:8000
  * Add
  * Test
* see dashboard
  * Dashboards
  * Kamon dashboard

more details in https://github.com/kamon-io/docker-grafana-graphite

## reading dashboard
Each component (server, reader, writer) is running in a separate container, hence in a different host so you have to change the host dropdown 
to see the metrics for the different components. The relevant metric is "# of processed messages per actor" in the "Actor Metrics" panel.




