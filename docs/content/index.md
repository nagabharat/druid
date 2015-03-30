---
layout: doc_page
---

Introduction to Druid
---------------------

Druid is an open source data store designed for [OLAP](http://en.wikipedia.org/wiki/Online_analytical_processing) queries on timeseries data.
This page is meant to provide readers with a high level overview of how Druid stores data, and the architecture of a Druid cluster.

## The Data

To frame our discussion, let's begin with an example data set (from online advertising):

    timestamp             publisher          advertiser  gender  country  click  price
    2011-01-01T01:01:35Z  bieberfever.com    google.com  Male    USA      0      0.65
    2011-01-01T01:03:63Z  bieberfever.com    google.com  Male    USA      0      0.62
    2011-01-01T01:04:51Z  bieberfever.com    google.com  Male    USA      1      0.45
    2011-01-01T01:00:00Z  ultratrimfast.com  google.com  Female  UK       0      0.87
    2011-01-01T02:00:00Z  ultratrimfast.com  google.com  Female  UK       0      0.99
    2011-01-01T02:00:00Z  ultratrimfast.com  google.com  Female  UK       1      1.53

This data set is composed of three distinct components. If you are familiar with OLAP terminology, the following concepts should not be new.

* **Timestamp column**: We treat timestamp separately because all of our queries
 center around the time axis.

* **Dimension columns**: Here we have four dimensions of publisher, advertiser, gender, and country.
They each represent an axis of the data that we’ve chosen to slice across.

* **Metric columns**: These are impressions, clicks and revenue. These represent values, usually numeric,
which are derived from an aggregation operation – such as count, sum, and mean (we also run variance and higher moment calculations).
Also known as measures in standard OLAP terminology.

Individually, the events are not very interesting, however, summarizations of this type of data can yield many useful insights.
Druid can optionally choose to summarize this raw data at ingestion time using a process we refer to as "roll-up".
Roll-up is a first-level aggregation operation over a selected set of dimensions, equivalent to (in pseudocode):

    GROUP BY timestamp, publisher, advertiser, gender, country
      :: impressions = COUNT(1),  clicks = SUM(click),  revenue = SUM(price)

The compacted version of our original raw data looks something like this:

     timestamp             publisher          advertiser  gender country impressions clicks revenue
     2011-01-01T01:00:00Z  ultratrimfast.com  google.com  Male   USA     1800        25     15.70
     2011-01-01T01:00:00Z  bieberfever.com    google.com  Male   USA     2912        42     29.18
     2011-01-01T02:00:00Z  ultratrimfast.com  google.com  Male   UK      1953        17     17.31
     2011-01-01T02:00:00Z  bieberfever.com    google.com  Male   UK      3194        170    34.01

In practice, we see that rolling up data can dramatically reduce the size of data that needs to be stored (often by a factor of 100).
This storage reduction does come at a cost; as we roll up data, we lose the ability to query individual events. Phrased another way,
the rollup granularity is the minimum granularity you will be able to query data at. Hence, Druid ingestion specs define this granularity as the `queryGranularity` of the data.

Druid is designed to perform single table operations and does not currently support joins.
Although many production setups instrument joins at the ETL level, data must be denormalized before it is loaded into Druid.

## Indexing the Data

Druid gets its speed in part from how it stores data. Borrowing ideas from search infrastructure,
Druid creates immutable snapshots of data, stored in data structures highly optimized for analytic queries.

Druid is a column store, which means each individual column is stored separately. Only the columns that pertain to a query are used
in that query, and Druid is pretty good about only scanning exactly what it needs for a query.
Different columns can also employ different compression methods. Different columns can also have different indexes associated with them.

## Sharding the Data

Druid shards are called `segments` and Druid always first shards data by time. In our compacted data set, we can create two segments, one for each hour of data.

For example:

Segment `sampleData_2011-01-01T01:00:00:00Z_2011-01-01T02:00:00:00Z_v1_0` contains

     2011-01-01T01:00:00Z  ultratrimfast.com  google.com  Male   USA     1800        25     15.70
     2011-01-01T01:00:00Z  bieberfever.com    google.com  Male   USA     2912        42     29.18


Segment `sampleData_2011-01-01T02:00:00:00Z_2011-01-01T03:00:00:00Z_v1_0` contains

     2011-01-01T02:00:00Z  ultratrimfast.com  google.com  Male   UK      1953        17     17.31
     2011-01-01T02:00:00Z  bieberfever.com    google.com  Male   UK      3194        170    34.01

Segments are self-contained containers for the time interval of data they hold. Segments
contain data stored in compressed column orientations, along with the indexes for those columns. Druid queries only understand how to
scan segments.

Segments are uniquely identified by a datasource, interval, version, and partition number.
Multiple segments may exist for the same interval of time for the same datasource. These segments form a `block` for an interval.
Druid queries will only complete if a `block` is complete. That is to say, if a block consists of 3 segments, such as:

`sampleData_2011-01-01T02:00:00:00Z_2011-01-01T03:00:00:00Z_v1_0`

`sampleData_2011-01-01T02:00:00:00Z_2011-01-01T03:00:00:00Z_v1_1`

`sampleData_2011-01-01T02:00:00:00Z_2011-01-01T03:00:00:00Z_v1_2`

All 3 segments must be loaded before a query for the interval `2011-01-01T02:00:00:00Z_2011-01-01T03:00:00:00Z` completes.

## Loading the Data

Druid currently has a couple of different ways of getting data into it. In the near future, we'd like to address this complexity and unify the various data ingestion methods.
For now, Druid has a "real-time" method of delivering current time data, and a batch loading mechanism to load static data. Both the real-time and batch loading
methods deal with creating immutable segments. In the case of real-time ingestion, mutable segments, which are also queryable, are created and periodically finalized into an
immutable form. In the case of batch ingestion, immutable segments are created directly.

Real-time ingestion in Druid is best effort. Much like other popular data infrastructure open source projects that deal with streams of data,
exactly once semantics are not guaranteed with real-time ingestion in Druid. Like the other projects, we have it on our roadmap to move towards a streaming only world.
One common approach to operating Druid is to have a real-time pipeline for recent insights, and a batch pipeline for the accurate copy of the data.

## The Druid Cluster

A Druid Cluster is composed of several different types of nodes. Each node is designed to do a small set of things very well.

### Historical Nodes

Historical nodes commonly form the backbone of a Druid cluster. Historical nodes download immutable segments locally and serve queries over those segments.
The nodes have a shared nothing architecture and know how to load segments, drop segments, and serve queries on segments.

### Broker Nodes

Broker nodes are what clients and applications query to get data from Druid. Broker nodes are responsible for scattering queries and gathering and merging results.
Broker nodes know what segments live where.

### Coordinator Nodes

Coordinator nodes manage segments on historical nodes in a cluster. Coordinator nodes tell historical nodes to load new segments, drop old segments, and move segments to load balance.

### Real-time Processing Nodes

Real-time processing in Druid can currently be done using standalone realtime nodes or using the indexing service. The real-time logic is common between these two services.
Real-time processing involves ingesting data, indexing the data (creating segments), and handing segments off to historical nodes. Data is queryable as soon as it is
 ingested by the realtime processing logic. The hand-off process is also lossless; data remains queryable throughout the entire process.

### External Dependencies

Druid has a couple of external dependencies for cluster operations.

* **Distributed Coordination** Druid relies on a distributed coordination service for intra-cluster communication. The only supported distributed coordination service right now is Zookeeper.

* **Metadata Storage** Druid relies on a metadata storage to store metadata about segments and configuration. Services that create segments write new entries to the metadata store
  and the coordinator nodes monitor the metadata store to know when new data needs to be loaded or old data needs to be dropped. The metadata store is not
  involved in the query path. MySQL and PostgreSQL are popular metadata stores.

* **Deep Storage** Deep storage acts as a permanent backup of segments. Services that create segments upload segments to deep storage and historical nodes download
segments from deep storage. Deep storage is not involved in the query path. S3 and HDFS are popular deep storages.

### High Availability Characteristics

Druid is designed to have no single point of failure. Different node types are able to fail without impacting the services of the other node types. To run a highly available Druid cluster, you should have at least 2 nodes of every node type running.

### Comprehensive Architecture

For a comprehensive look at Druid architecture, please read our [white paper](http://static.druid.io/docs/druid.pdf).

