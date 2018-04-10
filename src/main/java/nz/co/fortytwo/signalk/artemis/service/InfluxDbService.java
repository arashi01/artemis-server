package nz.co.fortytwo.signalk.artemis.service;

import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.CONFIG;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.CONTEXT;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.PATH;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.PUT;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.UPDATES;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.dot;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.label;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.meta;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.resources;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.sentence;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.source;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.sourceRef;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.sources;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.timestamp;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.type;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.value;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.values;
import static nz.co.fortytwo.signalk.artemis.util.SignalKConstants.vessels;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.influxdb.BatchOptions;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Point.Builder;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.influxdb.dto.QueryResult.Series;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;

import mjson.Json;
import nz.co.fortytwo.signalk.artemis.util.Util;

public class InfluxDbService {

	private static final String STR_VALUE = "strValue";
	private static final String LONG_VALUE = "longValue";
	private static final String DOUBLE_VALUE = "doubleValue";
	private static final String NULL_VALUE = "nullValue";
	private static Logger logger = LogManager.getLogger(InfluxDbService.class);
	private InfluxDB influxDB;
	private String dbName = "signalk";
	private boolean DEBUG = false;

	public InfluxDbService() {
		if (logger.isDebugEnabled()) {
			DEBUG = true;
		}
		setUpInfluxDb();
	}

	public void setUpInfluxDb() {
		influxDB = InfluxDBFactory.connect("http://localhost:8086", "admin", "admin");
		if (!influxDB.databaseExists(dbName))
			influxDB.createDatabase(dbName);
		influxDB.setDatabase(dbName);
		// String rpName = "aRetentionPolicy";
		// influxDB.createRetentionPolicy(rpName, dbName, "30d", "30m", 2,
		// true);
		// influxDB.setRetentionPolicy(rpName);
	
		influxDB.enableBatch(BatchOptions.DEFAULTS.exceptionHandler((failedPoints, throwable) -> {
			logger.error(throwable);
		}));
	}

	public void closeInfluxDb() {
		influxDB.close();
	}

	public void recurseJsonFull(Json json, NavigableMap<String, Json> map, String prefix) {
		for (Entry<String, Json> entry : json.asJsonMap().entrySet()) {
			if (DEBUG)
				logger.debug(entry.getKey() + "=" + entry.getValue());
			if (entry.getValue().isPrimitive() || entry.getValue().isNull() || entry.getValue().isArray() ||entry.getValue().has(value)) {
				map.put(prefix + entry.getKey(), entry.getValue());
				continue;
			}  
			recurseJsonFull(entry.getValue(), map, prefix + entry.getKey() + ".");
		
		}

	}

	/**
	 * Convert Delta JSON to map. Returns null if the json is not an update,
	 * otherwise return a map
	 * 
	 * @param node
	 * @return
	 * @throws Exception
	 */
	public NavigableMap<String, Json> parseDelta(Json node, NavigableMap<String, Json> temp) throws Exception {
		// avoid full signalk syntax
		if (node.has(vessels))
			return null;

		if (node.has(CONTEXT) && (node.has(UPDATES) || node.has(PUT))) {
			if (DEBUG)
				logger.debug("processing delta  " + node);
			// process it

			// go to context
			String ctx = node.at(CONTEXT).asString();
			ctx = Util.fixSelfKey(ctx);
			if (DEBUG)logger.debug("ctx: " + node);
			// Json pathNode = temp.addNode(path);
			Json updates = node.at(UPDATES);
			if (updates == null)
				updates = node.at(PUT);
			if (updates == null)
				return temp;

			for (Json update : updates.asJsonList()) {
				parseUpdate(temp, update, ctx);
			}

			if (DEBUG)
				logger.debug("processed delta  " + temp);
			return temp;
		}
		return null;

	}

	protected void parseUpdate(NavigableMap<String, Json> temp, Json update, String ctx) throws Exception {

		// grab values and add
		Json array = update.at(values);
		for (Json e : array.asJsonList()) {
			if (e == null || e.isNull() || !e.has(PATH))
				continue;
			String key = dot + e.at(PATH).asString();
			if(key.equals(dot))key="";
			e.delAt(PATH);
			// temp.put(ctx+"."+key, e.at(value).getValue());

			if (update.has(source)) {
				Json src = update.at(source);
				String srcRef=src.at(type).asString()+dot+src.at(label).asString();
				e.set(sourceRef, srcRef);
				//add sources
				recurseJsonFull(src,temp,sources+dot+srcRef+dot);
				
			}else{
				e.set(sourceRef, "self");
			}

			if (update.has(timestamp)) {
				if (DEBUG)logger.debug("put timestamp: " + ctx + key+":"+ e);
				e.set(timestamp, update.at(timestamp).asString());
			}else{
				e.set(timestamp,Util.getIsoTimeString());
			}
			
			if (e.has(value)) {
				if (DEBUG)logger.debug("put: " + ctx +  key+":"+ e);
				temp.put(ctx +  key, e);
			}
		}

	}

	public NavigableMap<String, Json> loadConfig() {
		Query query = new Query("select * from config group by skey order by time desc limit 1", dbName);
		QueryResult result = influxDB.query(query);
		NavigableMap<String, Json> map = new ConcurrentSkipListMap<>();
		if (result == null || result.getResults() == null)
			return map;
		result.getResults().forEach((r) -> {
			if (r.getSeries() == null ||r.getSeries()==null)
				return;
			r.getSeries().forEach((s) -> {
				if (DEBUG)
					logger.debug(s);
				if (s == null)
					return;
				String key = s.getName() + dot + s.getTags().get("skey");

				Object obj = getValue(LONG_VALUE, s, 0);
				if (obj != null)
					map.put(key, Json.make(Math.round((Double) obj)));

				obj = getValue(DOUBLE_VALUE, s, 0);
				if (obj != null)
					map.put(key, Json.make(obj));

				obj = getValue(STR_VALUE, s, 0);
				if (obj != null) {
					if (obj.equals("true")) {
						map.put(key, Json.make(true));
					} else if (obj.equals("false")) {
						map.put(key, Json.make(false));
					} else if (obj.toString().startsWith("[") && obj.toString().endsWith("]")) {
						map.put(key, Json.read(obj.toString()));
					} else {
						map.put(key, Json.make(obj));
					}
				}

			});
		});
		return map;
	}

	
	public NavigableMap<String, Json> loadData(NavigableMap<String, Json> map, String queryStr, String db){
		Query query = new Query(queryStr, db);
		QueryResult result = influxDB.query(query);
		//NavigableMap<String, Json> map = new ConcurrentSkipListMap<>();
		if(DEBUG)logger.debug(result);
		if(result==null || result.getResults()==null)return map;
		result.getResults().forEach((r)-> {
			if(DEBUG)logger.debug(r);
			if(r==null||r.getSeries()==null)return;
			r.getSeries().forEach(
				(s)->{
					if(DEBUG)logger.debug(s);
					if(s==null)return;
					String key = s.getName()+dot+s.getTags().get("uuid")+dot+s.getTags().get("skey");
					Json val = getJsonValue(s,0);
					boolean processed = false;
					//add timestamp and sourceRef
					
					
					if(key.endsWith(".sentence")){
							
						String senKey = StringUtils.substringBeforeLast(key,".");
						
						//make parent Json
						Json parent = map.get(senKey);
						if(parent==null){
							parent = Json.object();
							map.put(senKey,parent);
						}
						parent.set(sentence,val);
						processed=true;
						
					}
					if(key.contains(".meta.")){
						//add meta to parent of value
						String parentKey = StringUtils.substringBeforeLast(key,".meta.");
						String metaKey = StringUtils.substringAfterLast(key,".meta.");
						
						//make parent Json
						Json parent = map.get(parentKey);
						if(parent==null){
							parent = Json.object();
							map.put(parentKey,parent);
						}
						
						//add attributes
						addAtPath(parent,"meta."+metaKey, val);
						processed=true;
					}
					if(key.contains(".values.")){
						//add meta to parent of value
						String parentKey = StringUtils.substringBeforeLast(key,".values.");
						String valKey = StringUtils.substringAfterLast(key,".values.");
						String attr = StringUtils.substringAfterLast(valKey,".value.");
						valKey=StringUtils.substringBeforeLast(valKey,".");
						//make parent Json
						Json parent = map.get(parentKey);
						if(parent==null){
							parent = Json.object();
							map.put(parentKey,parent);
						}
						Json valuesJson = parent.at(values);
						if(valuesJson==null){
							valuesJson = Json.object();
							parent.set(values,valuesJson);
						}
						Json attrJson = valuesJson.at(valKey);
						if(attrJson==null){
							attrJson = Json.object();
							valuesJson.set(valKey,attrJson);
						}
						
						//add attributes
						extractValue(attrJson,s,attr,val,null);
						processed=true;
					}
					if(!processed && (key.endsWith(".value")||key.contains(".value."))){
						String attr = StringUtils.substringAfterLast(key,".value.");
					
						key = StringUtils.substringBeforeLast(key,".value");
						
						//make parent Json
						Json parent = map.get(key);
						if(parent==null)parent = Json.object();
						
						extractValue(parent,s, attr, val);
						
						map.put(key,parent);
						processed=true;
					}
					if(!processed) map.put(key,val);
					
				});
			});
		return map;
	}

	public NavigableMap<String, Json> loadSources(NavigableMap<String, Json> map, String queryStr, String db) {
		Query query = new Query(queryStr, db);
		QueryResult result = influxDB.query(query);
		// NavigableMap<String, Json> map = new ConcurrentSkipListMap<>();
		if (DEBUG)
			logger.debug(result);
		if (result == null || result.getResults() == null)
			return map;
		result.getResults().forEach((r) -> {
			if (DEBUG)
				logger.debug(r);
			if (r.getSeries() == null)
				return;
			r.getSeries().forEach((s) -> {
				if (DEBUG)
					logger.debug(s);
				if (s == null)
					return;
				String key = s.getName() + dot + s.getTags().get("skey");

				Object obj = getValue(LONG_VALUE, s, 0);
				if (obj != null)
					map.put(key, Json.make(Math.round((Double) obj)));

				obj = getValue(DOUBLE_VALUE, s, 0);
				if (obj != null)
					map.put(key, Json.make(obj));

				obj = getValue(STR_VALUE, s, 0);
				if (obj != null) {
					if (obj.equals("true")) {
						map.put(key, Json.make(true));
					} else if (obj.equals("false")) {
						map.put(key, Json.make(false));
					} else if (obj.toString().startsWith("[") && obj.toString().endsWith("]")) {
						map.put(key, Json.read(obj.toString()));
					} else {
						map.put(key, Json.make(obj));
					}
				}

			});
		});
		return map;
	}

	private Object getValue(String field, Series s, int row) {
		int i = s.getColumns().indexOf(field);
		if (i < 0)
			return null;
		return (s.getValues().get(row)).get(i);
	}

	private Json getJsonValue(Series s, int row) {
		Object obj = getValue(LONG_VALUE, s, 0);
		if (obj != null)
			return Json.make(Math.round((Double) obj));
		obj = getValue(NULL_VALUE, s, 0);
		if (obj != null && (Boolean)obj) return Json.nil();
		
		obj = getValue(DOUBLE_VALUE, s, 0);
		if (obj != null)
			return Json.make(obj);

		obj = getValue(STR_VALUE, s, 0);
		if (obj != null) {
			if (obj.equals("true")) {
				return Json.make(true);
			}
			if (obj.equals("false")) {
				return Json.make(false);
			}
			if (obj.toString().startsWith("[") && obj.toString().endsWith("]")) {
				return Json.read(obj.toString());
			}
			return Json.make(obj);

		}
		return Json.nil();

	}

	public void save(NavigableMap<String, Json> map) {
		map.forEach((k, v) -> save(k, v));
		influxDB.flush();
	}

	public void save(String k, Json v) {
		logger.debug("Save json: " + k + "=" + v.toString());
		String srcRef = (v.isObject() && v.has(sourceRef) ? v.at(sourceRef).asString() : "self");
		long tStamp = (v.isObject() && v.has(timestamp) ? Util.getMillisFromIsoTime(v.at(timestamp).asString())
				: System.currentTimeMillis());

		if (v.isPrimitive()|| v.isBoolean()) {
			if (DEBUG)
				logger.debug("Save primitive: " + k + "=" + v.toString());
			saveData(k, srcRef, tStamp, v.getValue());	
			return;
		}
		if (v.isNull()) {
			if (DEBUG)
				logger.debug("Save null: " + k + "=" + v.toString());
			saveData(k, srcRef, tStamp, null);
			return;
		}
		if (v.isArray()) {
			if (DEBUG)
				logger.debug("Save array: " + k + "=" + v.toString());
			saveData(k, srcRef, tStamp, v.toString());
			return;
		}
		if (v.has(sentence)) {
			saveData(k + dot + sentence, srcRef, tStamp, v.at(sentence));
		}
		if (v.has(meta)) {
			for (Entry<String, Json> i : v.at(meta).asJsonMap().entrySet()) {
				if (DEBUG)
					logger.debug("Save meta: " + i.getKey() + "=" + i.getValue());
				saveData(k + dot + meta + dot + i.getKey(), srcRef, tStamp, i.getValue());
			}
		}
		
		if (v.has(values)) {
			for (Entry<String, Json> i : v.at(values).asJsonMap().entrySet()) {
				if (DEBUG)
					logger.debug("Save values: " + i.getKey() + "=" + i.getValue());
				save(k + dot + values + dot + i.getKey(),i.getValue());
			}
		}

		if (v.has(value)&& v.at(value).isObject()) {
			for (Entry<String, Json> i : v.at(value).asJsonMap().entrySet()) {
				if (DEBUG)
					logger.debug("Save value object: " + i.getKey() + "=" + i.getValue());
				saveData(k + dot + value + dot + i.getKey(), srcRef, tStamp, i.getValue());
			}
			return;
		}

		if (DEBUG)
			logger.debug("Save value: " + k + "=" + v.toString());
		saveData(k + dot + value, srcRef, tStamp, v.at(value));

		return;
	}

	private void addAtPath(Json parent, String path, Json val) {
		String[] pathArray = StringUtils.split(path, ".");
		Json node = parent;
		for (int x = 0; x < pathArray.length; x++) {
			// add last
			if (x == (pathArray.length - 1)) {
				if (DEBUG)
					logger.debug("finish:" + pathArray[x]);
				node.set(pathArray[x], val);
				break;
			}
			// get next node
			Json next = node.at(pathArray[x]);
			if (next == null) {
				next = Json.object();
				node.set(pathArray[x], next);
				if (DEBUG)
					logger.debug("add:" + pathArray[x]);
			}
			node = next;
		}

	}

	private void extractValue(Json parent, Series s, String attr, Json val) {
		Object sr = getValue("sourceRef", s, 0);
		extractValue(parent,s,attr,val,sr);
	}
	
	private void extractValue(Json parent, Series s, String attr, Json val, Object srcref) {
		Object ts = getValue("time", s, 0);
		if (ts != null) {
			// make predictable 3 digit nano ISO format
			ts = Util.getIsoTimeString(DateTime.parse((String)ts, ISODateTimeFormat.dateTimeParser()).getMillis());
			parent.set(timestamp, Json.make(ts));
		}
		if (srcref != null) {
			parent.set(sourceRef, Json.make(srcref));
		}
		
		// check if its an object value
		if (StringUtils.isNotBlank(attr)) {
			Json valJson = parent.at(value);
			if (valJson == null) {
				valJson = Json.object();
				parent.set(value, valJson);
			}
			valJson.set(attr, val);
		} else {
			parent.set(value, val);
		}

	}


	protected void saveData(String key, String sourceRef, long millis, Object value) {
		if (DEBUG)
			logger.debug("save "+value.getClass().getSimpleName()+":" + key);
		String[] path = StringUtils.split(key, '.');
		String field = getFieldType(value);
		Builder point = null;
		switch (path[0]) {
		case vessels:
			point = Point.measurement(path[0]).time(millis, TimeUnit.MILLISECONDS).tag("sourceRef", sourceRef)
					.tag("uuid", path[1]).tag("skey", String.join(".", ArrayUtils.subarray(path, 2, path.length)));
			influxDB.write(addPoint(point, field, value));
			break;
		case resources:
			point = Point.measurement(path[0]).time(millis, TimeUnit.MILLISECONDS).tag("sourceRef", sourceRef)
					.tag("uuid", path[1]).tag("skey", String.join(".", ArrayUtils.subarray(path, 1, path.length)));
			influxDB.write(addPoint(point, field, value));
			break;
		case sources:
			point = Point.measurement(path[0]).time(millis, TimeUnit.MILLISECONDS).tag("sourceRef", path[1])
					.tag("skey", String.join(".", ArrayUtils.subarray(path, 1, path.length)));
			influxDB.write(addPoint(point, field, value));
			break;
		case CONFIG:
			point = Point.measurement(path[0]).time(millis, TimeUnit.MILLISECONDS).tag("sourceRef", sourceRef)
					.tag("uuid", path[1]).tag("skey", String.join(".", ArrayUtils.subarray(path, 1, path.length)));
			influxDB.write(addPoint(point, field, value));
			break;
		default:
			break;
		}
	}

	private Point addPoint(Builder point, String field, Object value) {
		if(DEBUG)logger.debug("addPoint:"+field+":"+value);
		if(value==null)return point.addField(field,true).build();
		if(value instanceof Json){
			if(((Json)value).isString()){
				value=((Json)value).asString();
			}else if(((Json)value).isNull()){
				value=true;
			}else if(((Json)value).isArray()){
				value=((Json)value).toString();
			}else{
				value=((Json)value).getValue();
			}
		}
		if(value instanceof Boolean)return point.addField(field,(Boolean)value).build();
		if(value instanceof Double)return point.addField(field,(Double)value).build();
		if(value instanceof Float)return point.addField(field,(Double)value).build();
		if(value instanceof BigDecimal)return point.addField(field,((BigDecimal)value).doubleValue()).build();
		if(value instanceof Long)return point.addField(field,(Long)value).build();
		if(value instanceof Integer)return point.addField(field,((Integer)value).longValue()).build();
		if(value instanceof BigInteger)return point.addField(field,((BigInteger)value).longValue()).build();
		if(value instanceof String)return point.addField(field,(String)value).build();
		if(DEBUG)logger.debug("addPoint: unknown type:"+field+":"+value);
		return null;
	}

	private String getFieldType(Object value) {
		if(DEBUG)logger.debug("getFieldType:"+value.getClass().getName()+":"+value);
		if(value==null)return NULL_VALUE;
		if(value instanceof Json){
			if(((Json)value).isNull())return NULL_VALUE;
			if(((Json)value).isArray())return STR_VALUE;
			value=((Json)value).getValue();
		}
		if(value instanceof Double)return DOUBLE_VALUE;
		if(value instanceof BigDecimal)return DOUBLE_VALUE;
		if(value instanceof Long)return LONG_VALUE;
		if(value instanceof BigInteger)return LONG_VALUE;
		if(value instanceof Integer)return LONG_VALUE;
		if(value instanceof String)return STR_VALUE;
		
		if(DEBUG)logger.debug("getFieldType:unknown type:"+value.getClass().getName()+":"+value);
		return null;
	}

	public void close() {
		influxDB.close();
	}

	public InfluxDB getInfluxDB() {
		return influxDB;
	}
}
