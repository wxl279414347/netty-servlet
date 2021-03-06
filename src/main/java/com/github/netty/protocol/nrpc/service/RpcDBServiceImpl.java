package com.github.netty.protocol.nrpc.service;

import com.github.netty.core.util.ExpiryLRUMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

/**
 * RpcDBServiceImpl
 * @author wangzihao
 */
public class RpcDBServiceImpl implements RpcDBService {
    private final Map<String, RpcDBExpiryLRUMap<String,byte[]>> memExpiryGroupMap = new HashMap<>(16);
    private static final String SHARING_GROUP = "/sharing";

    @Override
    public boolean exist2(String key, String group) {
        return getMemExpiryMap(group).containsKey(key);
    }

    @Override
    public void put(String key, byte[] data) {
        put4(key,data, -1, SHARING_GROUP);
    }

    @Override
    public void put3(String key, byte[] data, int expireSecond) {
        put4(key,data,expireSecond,SHARING_GROUP);
    }

    @Override
    public void put4(String key, byte[] data, int expireSecond, String group) {
        getMemExpiryMap(group).put(key,data,expireSecond);
    }

    @Override
    public int count(String group) {
        Map map = memExpiryGroupMap.get(group);
        if(map == null){
            return 0;
        }
        return map.size();
    }

    @Override
    public boolean exist(String key) {
        return exist2(key,SHARING_GROUP);
    }

    @Override
    public byte[] get(String key) {
        return get2(key,SHARING_GROUP);
    }

    @Override
    public byte[] get2(String key, String group) {
        return getMemExpiryMap(group).get(key);
    }

    @Override
    public void changeKey(String oldKey, String newKey) {
        changeKey3(oldKey,newKey,SHARING_GROUP);
    }

    @Override
    public void changeKey3(String oldKey, String newKey, String group) {
	     RpcDBExpiryLRUMap<String, byte[]> memExpiryMap = getMemExpiryMap(group);
	    memExpiryMap.putNode(newKey,memExpiryMap.removeNode(oldKey));
    }

    @Override
    public void remove(String key) {
        remove2(key,SHARING_GROUP);
    }

    @Override
    public void remove2(String key, String group) {
        getMemExpiryMap(group).remove(key);
    }

    @Override
    public void removeBatch(List<String> keys) {
        removeBatch2(keys,SHARING_GROUP);
    }

	@Override
	public void setMaxSize2(Integer maxSize, String group) {
		getMemExpiryMap(group).setMaxSize(maxSize);
	}

	@Override
	public void setMaxSize(Integer maxSize) {
		setMaxSize2(maxSize,SHARING_GROUP);
	}

	@Override
    public void removeBatch2(List<String> keys, String group) {
        if(keys == null || keys.isEmpty()){
            return;
        }

	    RpcDBExpiryLRUMap<String, byte[]> map = getMemExpiryMap(group);
        if(keys instanceof RandomAccess) {
            int size = keys.size();
            for (int i=0; i<size; i++){
                String key = keys.get(i);
                map.remove(key);
            }
        }else {
            for (String key : keys) {
                map.remove(key);
            }
        }
    }

    private RpcDBExpiryLRUMap<String, byte[]> getMemExpiryMap(String group) {
	    RpcDBExpiryLRUMap<String,byte[]> memExpiryMap = memExpiryGroupMap.get(group);
        if(memExpiryMap == null){
            synchronized (memExpiryGroupMap) {
                memExpiryMap = memExpiryGroupMap.get(group);
                if(memExpiryMap == null) {
                    memExpiryMap = new RpcDBExpiryLRUMap<>(-1);
                    memExpiryGroupMap.put(group, memExpiryMap);
                }
            }
        }
        return memExpiryMap;
    }

    private static class RpcDBExpiryLRUMap<K,V> extends ExpiryLRUMap<K,V> {
	    private int maxSize = -1;
	    RpcDBExpiryLRUMap(long defaultExpiryTime) {
		    super(defaultExpiryTime);
	    }
	    @Override
	    protected boolean removeEldestEntry(Entry<K, Node<V>> eldest) {
		    return size() > maxSize;
	    }

	    public void setMaxSize(int maxSize) {
		    this.maxSize = maxSize;
	    }
    }
}
