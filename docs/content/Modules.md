---
layout: doc_page
---

# Extending Druid With Custom Modules

Druid uses a module system that allows for the addition of extensions at runtime.

## Writing your own extensions

Druid's extensions leverage Guice in order to add things at runtime.  Basically, Guice is a framework for Dependency Injection, but we use it to hold the expected object graph of the Druid process.  Extensions can make any changes they want/need to the object graph via adding Guice bindings.  While the extensions actually give you the capability to change almost anything however you want, in general, we expect people to want to extend one of a few things.

1. Add a new deep storage implementation
1. Add a new Firehose
1. Add Aggregators
1. Add Complex metrics
1. Add new Query types
1. Add new Jersey resources

Extensions are added to the system via an implementation of `io.druid.initialization.DruidModule`.

### Creating a Druid Module

The DruidModule class is has two methods

1. A `configure(Binder)` method 
2. A `getJacksonModules()` method

The `configure(Binder)` method is the same method that a normal Guice module would have.

The `getJacksonModules()` method provides a list of Jackson modules that are used to help initialize the Jackson ObjectMapper instances used by Druid.  This is how you add extensions that are instantiated via Jackson (like AggregatorFactory and Firehose objects) to Druid.

### Registering your Druid Module

Once you have your DruidModule created, you will need to package an extra file in the `META-INF/services` directory of your jar.  This is easiest to accomplish with a maven project by creating files in the `src/main/resources` directory.  There are examples of this in the Druid code under the `cassandra-storage`, `hdfs-storage` and `s3-extensions` modules, for examples.

The file that should exist in your jar is

`META-INF/services/io.druid.initialization.DruidModule`

It should be a text file with a new-line delimited list of package-qualified classes that implement DruidModule like

```
io.druid.storage.cassandra.CassandraDruidModule
```

If your jar has this file, then when it is added to the classpath or as an extension, Druid will notice the file and will instantiate instances of the Module.  Your Module should have a default constructor, but if you need access to runtime configuration properties, it can have a method with @Inject on it to get a Properties object injected into it from Guice.

### Adding a new deep storage implementation

Check the `cassandra-storage`, `hdfs-storage` and `s3-extensions` modules for examples of how to do this.

The basic idea behind the extension is that you need to add bindings for your DataSegmentPusher and DataSegmentPuller objects.  The way to add them is something like (taken from HdfsStorageDruidModule)

``` java
Binders.dataSegmentPullerBinder(binder)
       .addBinding("hdfs")
       .to(HdfsDataSegmentPuller.class).in(LazySingleton.class);

Binders.dataSegmentPusherBinder(binder)
       .addBinding("hdfs")
       .to(HdfsDataSegmentPusher.class).in(LazySingleton.class);
```

`Binders.dataSegment*Binder()` is a call provided by the druid-api jar which sets up a Guice multibind "MapBinder".  If that doesn't make sense, don't worry about it, just think of it as a magical incantation.

`addBinding("hdfs")` for the Puller binder creates a new handler for loadSpec objects of type "hdfs".  For the Pusher binder it creates a new type value that you can specify for the `druid.storage.type` parameter.

`to(...).in(...);` is normal Guice stuff.

### Adding a new Firehose

There is an example of this in the `s3-extensions` module with the StaticS3FirehoseFactory.

Adding a Firehose is done almost entirely through the Jackson Modules instead of Guice.  Specifically, note the implementation

``` java
@Override
public List<? extends Module> getJacksonModules()
{
  return ImmutableList.of(
          new SimpleModule().registerSubtypes(new NamedType(StaticS3FirehoseFactory.class, "static-s3"))
  );
}
```

This is registering the FirehoseFactory with Jackson's polymorphic serde layer.  More concretely, having this will mean that if you specify a `"firehose": { "type": "static-s3", ... }` in your realtime config, then the system will load this FirehoseFactory for your firehose.

Note that inside of Druid, we have made the @JacksonInject annotation for Jackson deserialized objects actually use the base Guice injector to resolve the object to be injected.  So, if your FirehoseFactory needs access to some object, you can add a @JacksonInject annotation on a setter and it will get set on instantiation.

### Adding Aggregators

Adding AggregatorFactory objects is very similar to Firehose objects.  They operate purely through Jackson and thus should just be additions to the Jackson modules returned by your DruidModule.

### Adding Complex Metrics 

Adding ComplexMetrics is a little ugly in the current version.  The method of getting at complex metrics is through registration with the `ComplexMetrics.registerSerde()` method.  There is no special Guice stuff to get this working, just in your `configure(Binder)` method register the serde.

### Adding new Query types

Adding a new Query type requires the implementation of three interfaces.

1. `io.druid.query.Query`
1. `io.druid.query.QueryToolChest`
1. `io.druid.query.QueryRunnerFactory`

Registering these uses the same general strategy as a deep storage mechanism does.  You do something like

``` java
DruidBinders.queryToolChestBinder(binder)
            .addBinding(SegmentMetadataQuery.class)
            .to(SegmentMetadataQueryQueryToolChest.class);
    
DruidBinders.queryRunnerFactoryBinder(binder)
            .addBinding(SegmentMetadataQuery.class)
            .to(SegmentMetadataQueryRunnerFactory.class);
```

The first one binds the SegmentMetadataQueryQueryToolChest for usage when a SegmentMetadataQuery is used.  The second one does the same thing but for the QueryRunnerFactory instead.

### Adding new Jersey resources

Adding new Jersey resources to a module requires calling the following code to bind the resource in the module:

```java
Jerseys.addResource(binder, NewResource.class);
```
