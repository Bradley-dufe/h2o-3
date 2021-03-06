package water.api;

import hex.ModelBuilder;
import water.TypeMap;
import water.util.MarkdownBuilder;

import java.util.Map;

/*
 * Docs REST API handler, which provides endpoint handlers for the autogeneration of
 * Markdown (and in the future perhaps HTML and PDF) documentation for REST API endpoints
 * and payload entities (aka Schemas).
 */
public class MetadataHandler extends Handler {

  @SuppressWarnings("unused") // called through reflection by RequestServer
  /** Return a list of all REST API Routes and a Markdown Table of Contents. */
  public MetadataV3 listRoutes(int version, MetadataV3 docs) {
    MarkdownBuilder builder = new MarkdownBuilder();
    builder.comment("Preview with http://jbt.github.io/markdown-editor");
    builder.heading1("REST API Routes Table of Contents");
    builder.hline();

    builder.tableHeader("HTTP method", "URI pattern", "Input schema", "Output schema", "Summary");

    docs.routes = new RouteBase[RequestServer.numRoutes()];
    int i = 0;
    for (Route route : RequestServer.routes()) {
      docs.routes[i] = (RouteBase)Schema.schema(version, Route.class).fillFromImpl(route);

      // ModelBuilder input / output schema hackery
      MetadataV3 look = new MetadataV3();
      look.routes = new RouteBase[1];
      look.routes[0] = docs.routes[i];
      look.path = route._url_pattern.toString();
      look.http_method = route._http_method;
      look = fetchRoute(version, look);

      docs.routes[i].input_schema = look.routes[0].input_schema;
      docs.routes[i].output_schema = look.routes[0].output_schema;

      builder.tableRow(
              route._http_method,
              route._url_pattern.toString().replace("(?<", "{").replace(">.*)", "}"),
              Handler.getHandlerMethodInputSchema(route._handler_method).getSimpleName(),
              Handler.getHandlerMethodOutputSchema(route._handler_method).getSimpleName(),
              route._summary);
      i++;
    }

    docs.markdown = builder.toString();
    return docs;
  }

  @SuppressWarnings("unused") // called through reflection by RequestServer
  /** Return the metadata for a REST API Route, specified either by number or path. */
  public MetadataV3 fetchRoute(int version, MetadataV3 docs) {
    Route route = null;
    if (null != docs.path && null != docs.http_method) {
      route = RequestServer.lookup(docs.http_method, docs.path);
    } else {
      // Linear scan for the route, plus each route is asked for in-order
      // during doc-gen leading to an O(n^2) execution cost.
      int i = 0;
      for (Route r : RequestServer.routes())
        if (i++ == docs.num) { route = r; break; }
      // Crash-n-burn if route not found (old code thru an AIOOBE), so we
      // something similarly bad.
      docs.routes = new RouteBase[]{(RouteBase)Schema.schema(version, Route.class).fillFromImpl(route)};
    }

    Schema sinput, soutput;
    if( route._handler_class.equals(water.api.ModelBuilderHandler.class) ) {
      String ss[] = route._url_pattern_raw.split("/");
      String algoURLName = ss[3]; // {}/{3}/{ModelBuilders}/{gbm}/{parameters}
      int version2 = Integer.valueOf(ss[1]);
      String algoName = ModelBuilder.algoName(algoURLName); // gbm -> GBM; deeplearning -> DeepLearning
      String schemaDir = ModelBuilder.schemaDirectory(algoURLName);
      String inputSchemaName = schemaDir+algoName+"V"+version2;  // hex.schemas.GBMV3
      sinput = (Schema)TypeMap.theFreezable(TypeMap.onIce(inputSchemaName));
      sinput.init_meta();
      // hex.schemas.GBMModelV3$GBMModelOutputV3
      String outputSchemaName = schemaDir+algoName+"ModelV"+version2+"$"+algoName+"ModelOutputV"+version2;
      soutput= (Schema)TypeMap.theFreezable(TypeMap.onIce(outputSchemaName));
      soutput.init_meta();
    } else {
      sinput  = Schema.newInstance(Handler.getHandlerMethodInputSchema (route._handler_method));
      soutput = Schema.newInstance(Handler.getHandlerMethodOutputSchema(route._handler_method));
    }
    docs.routes[0].input_schema = sinput.getClass().getSimpleName();
    docs.routes[0].output_schema = soutput.getClass().getSimpleName();
    docs.routes[0].markdown = route.markdown(sinput,soutput).toString();
    return docs;
  }

  @SuppressWarnings("unused") // called through reflection by RequestServer
  @Deprecated
  /** Fetch the metadata for a Schema by its full internal classname, e.g. "hex.schemas.DeepLearningV2.DeepLearningParametersV2".  TODO: Do we still need this? */
  public MetadataV3 fetchSchemaMetadataByClass(int version, MetadataV3 docs) {
    docs.schemas = new SchemaMetadataBase[1];
    // NOTE: this will throw an exception if the classname isn't found:
    SchemaMetadataBase meta = (SchemaMetadataBase)Schema.schema(version, SchemaMetadata.class).fillFromImpl(SchemaMetadata.createSchemaMetadata(docs.classname));
    docs.schemas[0] = meta;
    return docs;
  }

  @SuppressWarnings("unused") // called through reflection by RequestServer
  /** Fetch the metadata for a Schema by its simple Schema name (e.g., "DeepLearningParametersV2"). */
  public MetadataV3 fetchSchemaMetadata(int version, MetadataV3 docs) {
    if ("void".equals(docs.schemaname)) {
      docs.schemas = new SchemaMetadataBase[0];
      return docs;
    }

    docs.schemas = new SchemaMetadataBase[1];
    // NOTE: this will throw an exception if the classname isn't found:
    SchemaMetadataBase meta = (SchemaMetadataBase)Schema.schema(version, SchemaMetadata.class).fillFromImpl(new SchemaMetadata(Schema.newInstance(docs.schemaname)));
    docs.schemas[0] = meta;
    return docs;
  }

  @SuppressWarnings("unused") // called through reflection by RequestServer
  /** Fetch the metadata for all the Schemas. */
  public MetadataV3 listSchemas(int version, MetadataV3 docs) {
    Map<String, Class<? extends Schema>> ss = Schema.schemas();
    docs.schemas = new SchemaMetadataBase[ss.size()];

    // NOTE: this will throw an exception if the classname isn't found:
    int i = 0;
    for (Class<? extends Schema> schema_class : ss.values()) {
      // No hardwired version! YAY!  FINALLY!
      docs.schemas[i++] = (SchemaMetadataBase)Schema.schema(version, SchemaMetadata.class).fillFromImpl(new SchemaMetadata(Schema.newInstance(schema_class)));
    }
    return docs;
  }
}
