package org.apache.kafka.common.serialization;

import java.util.Map;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

public class JSONSerializer implements Serializer<JSONObject> {
	@SuppressWarnings("unused")
	private String encoding = "UTF8";

	public void configure(Map<String, ?> configs, boolean isKey) {
		String propertyName = isKey ? "key.serializer.encoding" : "value.serializer.encoding";
		Object encodingValue = configs.get(propertyName);
		if (encodingValue == null)
			encodingValue = configs.get("serializer.encoding");
		if (encodingValue != null && encodingValue instanceof String)
			encoding = (String) encodingValue;
	}

	public byte[] serialize(String topic, JSONObject data) {
		if (data == null)
			return null;
		return JSON.toJSONBytes(data);
	}

	public void close() {
	}

}
