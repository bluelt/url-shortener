package io.stalk.mod.shortener.persister;

import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentMap;

import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Future;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.ServerAddress;

public class MongodbPersisterVerticle  extends BusModBase implements Handler<Message<JsonObject>>{

	private String address;
	private String host;
	private int port;

	private MongoClient mongo;
	private DB db;

	@Override
	public void start(final Future<Void> startedResult) {

		super.start();

		address = getOptionalStringConfig("address", "shortener.persister");
		host = getOptionalStringConfig("host", "localhost");
		port = getOptionalIntConfig("port", 27017);

		try {
			ServerAddress mongoAddress = new ServerAddress(host, port);
			mongo = new MongoClient(mongoAddress);
			db = mongo.getDB("url-shortener");

		} catch (UnknownHostException e) {
			logger.error(" >> mongodb connection error : ", e);
			e.printStackTrace();
		}

		eb.registerHandler(address, this, new AsyncResultHandler<Void>() {

			@Override
			public void handle(AsyncResult<Void> ar) {
				if (!ar.succeeded()) {
					ar.cause().printStackTrace();
					startedResult.setFailure(ar.cause());
				} else {
					startedResult.setResult(null);
				}

				logger.info(" >>> mongodb Persister is started");
			}

		});

	}

	@Override
	public void stop() {
		if (mongo != null) {
			mongo.close();
		}
	}

	@Override
	public void handle(Message<JsonObject> message) {

		String action = message.body().getString("action");

		printActionCount(action); // to debug or monitoring.

		switch (action) {
		case "create":
			createShortUrl(message);
			break;

		case "get":
			getShortUrl(message);
			break;

		default:
			sendError(message, "[action] must be specified!");
		}
	}

	protected void getShortUrl(Message<JsonObject> message) {

		DBCollection urls = db.getCollection("urls");

		DBObject query = new BasicDBObject("_id", message.body().getString("key"));

		DBObject res = urls.findOne(query);

		if(res != null){
			sendOK(message, new JsonObject(res.toString()));
		}else{
			sendError(message, "NOT EXISTED.");
		}

	}

	protected void createShortUrl(Message<JsonObject> message) {

		DBCollection seq = db.getCollection("seq");

		// 1. generate shortId (key) ;
		DBObject query = new BasicDBObject();
		query.put("_id", "urlShortener");

		DBObject change = new BasicDBObject("seq", 1);
		DBObject update = new BasicDBObject("$inc", change);

		DBObject res = seq.findAndModify(
				query,  new BasicDBObject(), new BasicDBObject(), false, update, true, true);

		int num = Integer.parseInt(res.get("seq").toString());
		String shortId = BijectiveUtil.encode(num);

		// 2. store new URL shortened.
		DBCollection urls = db.getCollection("urls");

		DBObject urlData = new BasicDBObject("_id", shortId);
		urlData.put("url", message.body().getString("url"));

		urls.insert(urlData);

		JsonObject reply = new JsonObject();
		reply.putString("key", shortId);
		reply.putString("url", message.body().getString("url"));

		reply.putString("status", "ok");

		message.reply(reply);
	}

	private int printActionCount(String action){

		ConcurrentMap<String, Integer> sharedMap = vertx.sharedData().getMap("count.action");
		int _cnt = 0;
		Integer _countObj = sharedMap.get(action);
		if(_countObj == null) {
			_cnt = 1;
		}else{
			_cnt = _countObj + 1;
		}

		sharedMap.put("create", _cnt);

		System.out.println(action+" #"+_cnt);

		return _cnt;
	}



}
