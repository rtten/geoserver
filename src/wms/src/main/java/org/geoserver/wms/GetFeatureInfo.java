/* Copyright (c) 2010 TOPP - www.openplans.org.  All rights reserved.
 * This code is licensed under the GPL 2.0 license, availible at the root
 * application directory.
 */
package org.geoserver.wms;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.opengis.wfs.FeatureCollectionType;
import net.opengis.wfs.WfsFactory;

import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ProjectionPolicy;
import org.geoserver.catalog.WMSLayerInfo;
import org.geoserver.platform.ServiceException;
import org.geotools.coverage.GridSampleDimension;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridCoverage2DReader;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.data.DataUtilities;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.data.ows.Layer;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.store.FilteringFeatureCollection;
import org.geotools.data.store.ReTypingFeatureCollection;
import org.geotools.data.wms.WebMapServer;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.factory.GeoTools;
import org.geotools.factory.Hints;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.SchemaException;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.filter.IllegalFilterException;
import org.geotools.filter.visitor.SimplifyingFilterVisitor;
import org.geotools.geometry.DirectPosition2D;
import org.geotools.geometry.TransformedDirectPosition;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.WMSMapLayer;
import org.geotools.parameter.Parameter;
import org.geotools.referencing.CRS;
import org.geotools.referencing.CRS.AxisOrder;
import org.geotools.renderer.lite.MetaBufferEstimator;
import org.geotools.renderer.lite.RendererUtilities;
import org.geotools.resources.geometry.XRectangle2D;
import org.geotools.styling.FeatureTypeStyle;
import org.geotools.styling.Rule;
import org.geotools.styling.Style;
import org.geotools.util.NullProgressListener;
import org.geotools.util.logging.Logging;
import org.geotools.wfs.v1_0.WFSConfiguration;
import org.geotools.xml.Parser;
import org.opengis.coverage.CannotEvaluateException;
import org.opengis.coverage.PointOutsideCoverageException;
import org.opengis.coverage.grid.GridEnvelope;
import org.opengis.feature.Feature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.feature.type.Name;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory2;
import org.opengis.filter.Or;
import org.opengis.geometry.DirectPosition;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.parameter.GeneralParameterDescriptor;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.datum.PixelInCell;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.Polygon;

/**
 * WMS GetFeatureInfo operation
 * 
 * @author Gabriel Roldan
 */
public class GetFeatureInfo {

    private static final Logger LOGGER = Logging.getLogger(GetFeatureInfo.class);

    private WMS wms;

    public GetFeatureInfo(final WMS wms) {
        this.wms = wms;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public FeatureCollectionType run(final GetFeatureInfoRequest request) throws ServiceException {

        List<FeatureCollection> results;

        results = execute(request);
        return buildResults(results);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private FeatureCollectionType buildResults(List<FeatureCollection> results) {

        FeatureCollectionType result = WfsFactory.eINSTANCE.createFeatureCollectionType();
        result.setTimeStamp(Calendar.getInstance());
        result.getFeature().addAll(results);

        return result;
    }

    @SuppressWarnings("rawtypes")
    private List<FeatureCollection> execute(final GetFeatureInfoRequest request)
            throws ServiceException {

        // use the layer of the QUERY_LAYERS parameter, not the LAYERS one
        List<MapLayerInfo> layers = request.getQueryLayers();

        // grab the list of filters from the GetMap request, we don't want
        // to return what the user explicitly excluded
        List filterList = request.getGetMapRequest().getFilter();
        Filter[] filters;

        if (filterList != null && filterList.size() > 0) {
            filters = (Filter[]) filterList.toArray(new Filter[filterList.size()]);
        } else {
            filters = new Filter[layers.size()];
        }

        // grab the list of styles for each query layer, we'll use them to
        // auto-evaluate the GetFeatureInfo radius if the user did not specify one
        List<Style> getMapStyles = request.getGetMapRequest().getStyles();
        Style[] styles = new Style[layers.size()];
        for (int i = 0; i < styles.length; i++) {
            List<MapLayerInfo> getMapLayers = request.getGetMapRequest().getLayers();
            final String targetLayer = layers.get(i).getName();
            for (int j = 0; j < getMapLayers.size(); j++) {
                if (getMapLayers.get(j).getName().equals(targetLayer)) {
                    if (getMapStyles != null && getMapStyles.size() > 0)
                        styles[i] = (Style) getMapStyles.get(j);
                    if (styles[i] == null)
                        styles[i] = getMapLayers.get(j).getDefaultStyle();
                    break;
                }
            }
        }

        try {
            return execute(request, styles, filters);
        } catch (ServiceException se) {
            throw se;
        } catch (Exception e) {
            throw new ServiceException("Internal error occurred", e);
        }
    }

    @SuppressWarnings("rawtypes")
    private List<FeatureCollection> execute(GetFeatureInfoRequest request, Style[] styles,
            Filter[] filters) throws Exception {

        final List<MapLayerInfo> requestedLayers = request.getQueryLayers();
        // delegate to subclasses the hard work
        final int x = request.getXPixel();
        final int y = request.getYPixel();
        final int buffer = request.getGetMapRequest().getBuffer();
        final List<Map<String, String>> viewParams = request.getGetMapRequest().getViewParams();
        final GetMapRequest getMapReq = request.getGetMapRequest();
        final CoordinateReferenceSystem requestedCRS = getMapReq.getCrs(); // optional, may be null

        // basic information about the request
        final int width = getMapReq.getWidth();
        final int height = getMapReq.getHeight();
        final ReferencedEnvelope bbox = new ReferencedEnvelope(getMapReq.getBbox(),
                getMapReq.getCrs());
        final double scaleDenominator = RendererUtilities.calculateOGCScale(bbox, width, null);
        final FilterFactory2 ff = CommonFactoryFinder.getFilterFactory2(GeoTools.getDefaultHints());

        List<FeatureCollection> results = new ArrayList<FeatureCollection>(requestedLayers.size());

        int maxFeatures = request.getFeatureCount();
        for (int i = 0; i < requestedLayers.size(); i++) {
            final MapLayerInfo layer = requestedLayers.get(i);
            
            // look at the property names
            String[] names;
            List<List<String>> propertyNames = request.getPropertyNames();
            if(propertyNames == null || propertyNames.size() == 0 || propertyNames.get(i) == null) {
                names = Query.ALL_NAMES;
            } else {
                List<String> layerPropNames = propertyNames.get(i);
                names = (String[]) layerPropNames.toArray(new String[layerPropNames.size()]);
            }

            // check cascaded WMS first, it's a special case
            if (layer.getType() == MapLayerInfo.TYPE_WMS) {
                List<FeatureCollection> cascadedResults;
                cascadedResults = handleGetFeatureInfoCascade(request, maxFeatures, layer);
                if (cascadedResults != null) {
                    for (FeatureCollection fc : cascadedResults) {
                        results.add(selectProperties(fc, names));
                    }
                }
                continue;
            } 
            final Style style = styles[i];
            // ok, internally rendered layer then, we check the style to see what's active
            final List<Rule> rules = getActiveRules(style, scaleDenominator);
            if (rules.size() == 0) {
                continue;
            }
            
            FeatureCollection collection = null;
            if (layer.getType() == MapLayerInfo.TYPE_VECTOR) {
                final Map<String, String> viewParam = viewParams != null ? viewParams.get(i) : null;
                collection = identifyVectorLayer(filters, x, y, buffer, viewParam,
                        requestedCRS, width, height, bbox, ff, results, i, layer, rules, maxFeatures, names);

            } else if (layer.getType() == MapLayerInfo.TYPE_RASTER) {
                final CoverageInfo cinfo = requestedLayers.get(i).getCoverage();
                final AbstractGridCoverage2DReader reader = (AbstractGridCoverage2DReader) cinfo
                        .getGridCoverageReader(new NullProgressListener(),
                                GeoTools.getDefaultHints());
                
                
                // set the requested position in model space for this request
                final Coordinate middle = pixelToWorld(x, y, bbox, width, height);
                DirectPosition position = new DirectPosition2D(requestedCRS, middle.x, middle.y);

                // change from request crs to coverage crs in order to compute a minimal request
                // area,
                // TODO this code need to be made much more robust
                if (requestedCRS != null) {
                    final CoordinateReferenceSystem targetCRS;
                    if(cinfo.getProjectionPolicy() == ProjectionPolicy.NONE) {
                        targetCRS = cinfo.getNativeCRS();
                    } else {
                        targetCRS = cinfo.getCRS();
                    }
                    final TransformedDirectPosition arbitraryToInternal = new TransformedDirectPosition(
                            requestedCRS, targetCRS, new Hints(Hints.LENIENT_DATUM_SHIFT,
                                    Boolean.TRUE));
                    try {
                        arbitraryToInternal.transform(position);
                    } catch (TransformException exception) {
                        throw new CannotEvaluateException("Unable to answer the geatfeatureinfo",
                                exception);
                    }
                    position = arbitraryToInternal;
                }
                // check that the provided point is inside the bbox for this coverage
                if (!reader.getOriginalEnvelope().contains(position)) {
                    continue;
                }

                // read from the request
                GeneralParameterValue[] parameters = wms.getWMSReadParameters(request.getGetMapRequest(), 
                        requestedLayers.get(i), filters[i], reader, true);
                collection = identifyRasterLayer(reader, position, parameters, cinfo, getMapReq);
                
                // apply attribute selection
                collection = selectProperties(collection, names);
            } else {
                LOGGER.log(Level.SEVERE,
                        "Can't perform feature info " + "requests on " + layer.getName()
                                + ", layer type not supported");
            }
            

            if (collection != null) {
                int size = collection.size();
                if(size != 0) {
                    results.add(collection);
                    
                    // don't return more than FEATURE_COUNT
                    maxFeatures -= size;
                    if(maxFeatures <= 0) {
                        break;
                    }
                }
            }
        }
        return results;
    }

    private FeatureCollection selectProperties(FeatureCollection collection, String[] names) throws IOException {
        if(names != Query.ALL_NAMES) {
            Query q = new Query(collection.getSchema().getName().getLocalPart(), Filter.INCLUDE, names);
            return DataUtilities.source(collection).getFeatures(q);
        } else {
            return collection;
        }
    }

    @SuppressWarnings("rawtypes")
    private FeatureCollection identifyRasterLayer(AbstractGridCoverage2DReader reader,
            DirectPosition position, GeneralParameterValue[] parameters, CoverageInfo cinfo,
            GetMapRequest getMapReq) throws Exception {

        // now get the position in raster space using the world to grid related to
        // corner
        final MathTransform worldToGrid = reader.getOriginalGridToWorld(PixelInCell.CELL_CORNER)
                .inverse();
        final DirectPosition rasterMid = worldToGrid.transform(position, null);
        // create a 20X20 rectangle aruond the mid point and then intersect with the
        // original range
        final Rectangle2D.Double rasterArea = new Rectangle2D.Double();
        rasterArea.setFrameFromCenter(rasterMid.getOrdinate(0), rasterMid.getOrdinate(1),
                rasterMid.getOrdinate(0) + 10, rasterMid.getOrdinate(1) + 10);
        final Rectangle integerRasterArea = rasterArea.getBounds();
        final GridEnvelope gridEnvelope = reader.getOriginalGridRange();
        final Rectangle originalArea = (gridEnvelope instanceof GridEnvelope2D) ? (GridEnvelope2D) gridEnvelope
                : new Rectangle();
        XRectangle2D.intersect(integerRasterArea, originalArea, integerRasterArea);
        // paranoiac check, did we fall outside the coverage raster area? This should
        // never really happne if the request is well formed.
        if (integerRasterArea.isEmpty()) {
            return null;
        }
        // now set the grid geometry for this request
        for (int k = 0; k < parameters.length; k++) {
            if (!(parameters[k] instanceof Parameter<?>))
                continue;

            final Parameter<?> parameter = (Parameter<?>) parameters[k];
            if (parameter.getDescriptor().getName()
                    .equals(AbstractGridFormat.READ_GRIDGEOMETRY2D.getName())) {
                //
                // create a suitable geometry for this request reusing the getmap (we
                // could probably optimize)
                //
                parameter.setValue(new GridGeometry2D(new GridEnvelope2D(integerRasterArea), reader
                        .getOriginalGridToWorld(PixelInCell.CELL_CENTER), reader.getCrs()));
            }

        }

        // get the group of parameters tha this reader supports
        final ParameterValueGroup readParametersDescriptor = reader.getFormat().getReadParameters();

        //
        // Setting coverage reading params.
        //

        /*
         * Test if the parameter "TIME" is present in the WMS request, and by the way in the reading
         * parameters. If it is the case, one can adds it to the request. If an exception is thrown,
         * we have nothing to do.
         */
        final List dateTime = getMapReq.getTime();
        final boolean hasTime = dateTime != null && dateTime.size() > 0;
        final List<GeneralParameterDescriptor> parameterDescriptors = readParametersDescriptor
                .getDescriptor().descriptors();
        if (hasTime)
            for (GeneralParameterDescriptor pd : parameterDescriptors) {

                // TIME
                if (pd.getName().getCode().equalsIgnoreCase("TIME")) {
                    final ParameterValue time = (ParameterValue) pd.createValue();
                    if (time != null) {
                        time.setValue(getMapReq.getTime());
                    }

                    // add to the list
                    GeneralParameterValue[] readParametersClone = new GeneralParameterValue[parameters.length + 1];
                    System.arraycopy(parameters, 0, readParametersClone, 0, parameters.length);
                    readParametersClone[parameters.length] = time;
                    parameters = readParametersClone;

                    // leave
                    break;
                }
            }

        // uncomment when the DIM_RANGE vendor parameter will be
        // enabled
        // try {
        // ParameterValue dimRange =
        // reader.getFormat().getReadParameters()
        // .parameter("DIM_RANGE");
        // if (dimRange != null && request.getDimRange() !=
        // null) {
        // dimRange.setValue(request.getDimRange());
        // }
        // } catch (ParameterNotFoundException p) {
        // }

        /*
         * Test if the parameter "TIME" is present in the WMS request, and by the way in the reading
         * parameters. If it is the case, one can adds it to the request. If an exception is thrown,
         * we have nothing to do.
         */
        final double elevationValue = getMapReq.getElevation();
        final boolean hasElevation = !Double.isNaN(elevationValue);
        if (hasElevation)
            for (GeneralParameterDescriptor pd : parameterDescriptors) {

                // ELEVATION
                if (pd.getName().getCode().equalsIgnoreCase("ELEVATION")) {
                    final ParameterValue elevation = (ParameterValue) pd.createValue();
                    if (elevation != null) {
                        elevation.setValue(getMapReq.getElevation());
                    }

                    // add to the list
                    GeneralParameterValue[] readParametersClone = new GeneralParameterValue[parameters.length + 1];
                    System.arraycopy(parameters, 0, readParametersClone, 0, parameters.length);
                    readParametersClone[parameters.length] = elevation;
                    parameters = readParametersClone;

                    // leave
                    break;
                }
            }

        final GridCoverage2D coverage = (GridCoverage2D) reader.read(parameters);
        if (coverage == null) {
            if (LOGGER.isLoggable(Level.FINE))
                LOGGER.fine("Unable to load raster data for this request.");
            return null;
        }

        FeatureCollection pixel = null;
        try {
            final double[] pixelValues = coverage.evaluate(position, (double[]) null);
            pixel = wrapPixelInFeatureCollection(coverage, pixelValues, cinfo.getQualifiedName());
        } catch (PointOutsideCoverageException e) {
            // it's fine, users might legitimately query point outside, we just don't
            // return anything
        }
        return pixel;
    }

    @SuppressWarnings("rawtypes")
    private FeatureCollection identifyVectorLayer(Filter[] filters,
            final int x, final int y, final int buffer, final Map<String, String> viewParams,
            final CoordinateReferenceSystem requestedCRS, final int width, final int height,
            final ReferencedEnvelope bbox, final FilterFactory2 ff,
            List<FeatureCollection> results, int i, final MapLayerInfo layer, final List<Rule> rules,
            final int maxFeatures, final String[] propertyNames)
            throws IOException {

        CoordinateReferenceSystem dataCRS = layer.getCoordinateReferenceSystem();

        // compute the request radius
        double radius;
        if (buffer <= 0) {
            Integer layerBuffer = null;
            final LayerInfo layerInfo = layer.getLayerInfo();
            if (layerInfo != null) { // it is a local layer
                layerBuffer = layerInfo.getMetadata().get(LayerInfo.BUFFER, Integer.class);
            }
            if (layerBuffer != null && layerBuffer > 0) {
                radius = layerBuffer / 2.0;
            } else {
                // estimate the radius given the currently active rules
                MetaBufferEstimator estimator = new MetaBufferEstimator();
                for (Rule rule : rules) {
                    rule.accept(estimator);
                }

                if (estimator.getBuffer() < 6.0 || !estimator.isEstimateAccurate()) {
                    radius = 3.0;
                } else {
                    radius = estimator.getBuffer() / 2.0;
                }
            }
        } else {
            radius = buffer;
        }

        // make sure we don't go overboard, the admin might have set a maximum
        int maxRadius = wms.getMaxBuffer();
        if (maxRadius > 0 && radius > maxRadius)
            radius = maxRadius;

        Polygon pixelRect = getEnvelopeFilter(x, y, width, height, bbox, radius);
        if ((requestedCRS != null) && !CRS.equalsIgnoreMetadata(dataCRS, requestedCRS)) {
            try {
                MathTransform transform = CRS.findMathTransform(requestedCRS, dataCRS, true);
                pixelRect = (Polygon) JTS.transform(pixelRect, transform); // reprojected
            } catch (MismatchedDimensionException e) {
                LOGGER.severe(e.getLocalizedMessage());
            } catch (TransformException e) {
                LOGGER.severe(e.getLocalizedMessage());
            } catch (FactoryException e) {
                LOGGER.severe(e.getLocalizedMessage());
            }
        }

        final FeatureSource<? extends FeatureType, ? extends Feature> featureSource;
        featureSource = layer.getFeatureSource(false);
        FeatureType schema = featureSource.getSchema();

        Filter getFInfoFilter = null;
        try {
            GeometryDescriptor geometryDescriptor = schema.getGeometryDescriptor();
            String localName = geometryDescriptor.getLocalName();
            getFInfoFilter = ff.intersects(ff.property(localName), ff.literal(pixelRect));
        } catch (IllegalFilterException e) {
            e.printStackTrace();
            throw new ServiceException("Internal error : " + e.getMessage(), e);
        }

        // include the eventual layer definition filter
        if (filters[i] != null) {
            getFInfoFilter = ff.and(getFInfoFilter, filters[i]);
        }

        // see if we can include the rule filters as well, if too many we'll do them in
        // memory
        Filter postFilter = Filter.INCLUDE;
        Filter rulesFilters = buildRulesFilter(ff, rules);
        if (!(rulesFilters instanceof Or)
                || (rulesFilters instanceof Or && ((Or) rulesFilters).getChildren().size() <= 20)) {
            getFInfoFilter = ff.and(getFInfoFilter, rulesFilters);
        } else {
            postFilter = rulesFilters;
        }

        String typeName = schema.getName().getLocalPart();
        Query q = new Query(typeName, null, getFInfoFilter, maxFeatures,
                propertyNames, null);

        // handle sql view params
        if (viewParams != null && viewParams.size() > 0) {
            q.setHints(new Hints(Hints.VIRTUAL_TABLE_PARAMETERS, viewParams));
        }

        FeatureCollection<? extends FeatureType, ? extends Feature> match;
        match = featureSource.getFeatures(q);

        // if we could not include the rules filter into the query, post process in
        // memory
        if (!Filter.INCLUDE.equals(postFilter)) {
            match = DataUtilities.simple(new FilteringFeatureCollection(match, postFilter));
        }

        // this was crashing Gml2FeatureResponseDelegate due to not setting
        // the featureresults, thus not being able of querying the SRS
        // if (match.getCount() > 0) {
        return match;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private List<FeatureCollection> handleGetFeatureInfoCascade(GetFeatureInfoRequest request,
            int maxFeatures,
            MapLayerInfo layerInfo) throws Exception {

        final int x = request.getXPixel();
        final int y = request.getYPixel();
        WMSLayerInfo info = (WMSLayerInfo) layerInfo.getResource();
        WebMapServer wms = info.getStore().getWebMapServer(null);
        Layer layer = info.getWMSLayer(null);

        CoordinateReferenceSystem crs = request.getGetMapRequest().getCrs(); 
        if(crs == null)  {
            // use the native one
            crs = info.getCRS();
        }
        ReferencedEnvelope bbox = new ReferencedEnvelope(request.getGetMapRequest().getBbox(), crs);
        int width = request.getGetMapRequest().getWidth();
        int height = request.getGetMapRequest().getHeight();

        // we can cascade GetFeatureInfo on queryable layers and if the GML mime type is supported
        if (!layer.isQueryable()) {
            return null;
        }

        List<String> infoFormats;
        infoFormats = wms.getCapabilities().getRequest().getGetFeatureInfo().getFormats();
        if (!infoFormats.contains("application/vnd.ogc.gml")) {
            return null;
        }

        // the wms layer does request in a CRS that's compatible with the WMS server srs
        // list,
        // we may need to transform
        WMSMapLayer ml = new WMSMapLayer(wms, layer);
        // delegate to the web map layer as there's quite a bit of reprojection magic
        // code
        // that we want to be consistently reproduced for GetFeatureInfo as well
        final InputStream is = ml.getFeatureInfo(bbox, width, height, x, y,
                "application/vnd.ogc.gml", maxFeatures);
        List<FeatureCollection> results = null;
        try {
            Parser parser = new Parser(new WFSConfiguration());
            parser.setStrict(false);
            Object result = parser.parse(is);
            if (result instanceof FeatureCollectionType) {
                FeatureCollectionType fcList = (FeatureCollectionType) result;
                results = fcList.getFeature();
                
                List<FeatureCollection> retypedResults = 
                    new ArrayList<FeatureCollection>(results.size());
                
                // retyping feature collections to replace name and namespace 
                // from cascading server with our local WMSLayerInfo
                for (Iterator it = results.iterator(); it.hasNext();) {
                    SimpleFeatureCollection fc = (SimpleFeatureCollection) it.next();                       
                    SimpleFeatureType ft = fc.getSchema();
                                    
                    SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();                    
                    builder.init(ft);                                                                       
                    
                    builder.setName(info.getName());
                    builder.setNamespaceURI(info.getNamespace().getURI());
                   
                    FeatureCollection rfc = 
                        new ReTypingFeatureCollection(fc, builder.buildFeatureType());
                    
                    
                    
                    retypedResults.add(rfc);
                }
                results = retypedResults;                
            }
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Tried to parse GML2 response, but failed", t);
        } finally {
            is.close();
        }
        return results;
    }

    private Filter buildRulesFilter(org.opengis.filter.FilterFactory ff, List<Rule> rules) {
        // build up a or of all the rule filters
        List<Filter> filters = new ArrayList<Filter>();
        for (Rule rule : rules) {
            if (rule.getFilter() == null || rule.isElseFilter())
                return Filter.INCLUDE;
            filters.add(rule.getFilter());
        }
        // not or and and simplify (if there is any include/exclude we'll get
        // a very simple result ;-)
        Filter or = ff.or(filters);
        SimplifyingFilterVisitor simplifier = new SimplifyingFilterVisitor();
        return (Filter) or.accept(simplifier, null);
    }

    /**
     * Selects the rules active at this zoom level
     * 
     * @param style
     * @param scaleDenominator
     * @return
     */
    private List<Rule> getActiveRules(Style style, double scaleDenominator) {
        List<Rule> result = new ArrayList<Rule>();

        for (FeatureTypeStyle fts : style.getFeatureTypeStyles()) {
            for (Rule r : fts.rules()) {
                if ((r.getMinScaleDenominator() <= scaleDenominator)
                        && (r.getMaxScaleDenominator() > scaleDenominator)) {
                    result.add(r);
                }
            }
        }
        return result;
    }

    private Polygon getEnvelopeFilter(int x, int y, int width, int height, ReferencedEnvelope bbox,
            double radius) {
        Coordinate upperLeft = pixelToWorld(x - radius, y - radius, bbox, width, height);
        Coordinate lowerRight = pixelToWorld(x + radius, y + radius, bbox, width, height);

        Coordinate[] coords = new Coordinate[5];
        coords[0] = upperLeft;
        coords[1] = new Coordinate(lowerRight.x, upperLeft.y);
        coords[2] = lowerRight;
        coords[3] = new Coordinate(upperLeft.x, lowerRight.y);
        coords[4] = coords[0];

        GeometryFactory geomFac = new GeometryFactory();
        LinearRing boundary = geomFac.createLinearRing(coords); // this needs to be done with each
                                                                // FT so it can be reprojected
        Polygon pixelRect = geomFac.createPolygon(boundary, null);
        return pixelRect;
    }

    private SimpleFeatureCollection wrapPixelInFeatureCollection(GridCoverage2D coverage,
            double[] pixelValues, Name coverageName) throws SchemaException {

        GridSampleDimension[] sampleDimensions = coverage.getSampleDimensions();

        SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.setName(coverageName);
        final Set<String> bandNames = new HashSet<String>();
        for (int i = 0; i < sampleDimensions.length; i++) {
            String name = sampleDimensions[i].getDescription().toString();
            // GEOS-2518
            if (bandNames.contains(name))
                // it might happen again that the name already exists but it pretty difficult I'd
                // say
                name = new StringBuilder(name).append("_Band").append(i).toString();
            bandNames.add(name);
            builder.add(name, Double.class);
        }
        SimpleFeatureType gridType = builder.buildFeatureType();

        Double[] values = new Double[pixelValues.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = new Double(pixelValues[i]);
        }
        return DataUtilities.collection(SimpleFeatureBuilder.build(gridType, values, ""));
    }

    /**
     * Converts a coordinate expressed on the device space back to real world coordinates. Stolen
     * from LiteRenderer but without the need of a Graphics object
     * 
     * @param x
     *            horizontal coordinate on device space
     * @param y
     *            vertical coordinate on device space
     * @param map
     *            The map extent
     * @param width
     *            image width
     * @param height
     *            image height
     * 
     * @return The correspondent real world coordinate
     * 
     * @throws RuntimeException
     */
    private Coordinate pixelToWorld(double x, double y, ReferencedEnvelope map, double width, double height) {
        // set up the affine transform and calculate scale values
        AffineTransform at = worldToScreenTransform(map, width, height);

        Point2D result = null;

        try {
            result = at.inverseTransform(new java.awt.geom.Point2D.Double(x, y),
                    new java.awt.geom.Point2D.Double());
        } catch (NoninvertibleTransformException e) {
            throw new RuntimeException(e);
        }

        Coordinate c = new Coordinate(result.getX(), result.getY());

        return c;
    }

    /**
     * Sets up the affine transform. Stolen from liteRenderer code.
     * 
     * @param mapExtent
     *            the map extent
     * @param width
     *            the screen size
     * @param height
     * 
     * @return a transform that maps from real world coordinates to the screen
     */
    private AffineTransform worldToScreenTransform(ReferencedEnvelope mapExtent, double width, double height) {
        
        //the transformation depends on an x/y ordering, if we have a lat/lon crs swap it
        CoordinateReferenceSystem crs = mapExtent.getCoordinateReferenceSystem();
        boolean swap = crs != null && CRS.getAxisOrder(crs) == AxisOrder.NORTH_EAST;
        if (swap) {
            mapExtent = new ReferencedEnvelope(mapExtent.getMinY(), mapExtent.getMaxY(), 
                mapExtent.getMinX(), mapExtent.getMaxX(), null);
        }
        
        double scaleX = (double) width / mapExtent.getWidth();
        double scaleY = (double) height / mapExtent.getHeight();

        double tx = -mapExtent.getMinX() * scaleX;
        double ty = (mapExtent.getMinY() * scaleY) + height;

        AffineTransform at = new AffineTransform(scaleX, 0.0d, 0.0d, -scaleY, tx, ty);

        //if we swapped concatenate a transform that swaps back
        if (swap) {
            at.concatenate(new AffineTransform(0, 1, 1, 0, 0, 0));
        }

        return at;
    }

}