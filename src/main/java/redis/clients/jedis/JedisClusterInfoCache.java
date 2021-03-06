package redis.clients.jedis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.util.SafeEncoder;

public class JedisClusterInfoCache {
  private final Map<String, JedisPool> nodes = new HashMap<String, JedisPool>();
  private final Map<Integer, JedisPool> slots = new HashMap<Integer, JedisPool>();

  private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
  private final Lock r = rwl.readLock();
  private final Lock w = rwl.writeLock();
  private volatile boolean rediscovering;
  private final GenericObjectPoolConfig poolConfig;

  private int connectionTimeout;
  private int soTimeout;
  private String password;
  private String clientName;

  private boolean ssl;
  private SSLSocketFactory sslSocketFactory;
  private SSLParameters sslParameters;
  private HostnameVerifier hostnameVerifier;
  private JedisClusterHostAndPortMap hostAndPortMap;

  private static final int MASTER_NODE_INDEX = 2;

  public JedisClusterInfoCache(final GenericObjectPoolConfig poolConfig, int timeout) {
    this(poolConfig, timeout, timeout, null, null);
  }

  public JedisClusterInfoCache(final GenericObjectPoolConfig poolConfig,
      final int connectionTimeout, final int soTimeout, final String password, final String clientName) {
    this(poolConfig, connectionTimeout, soTimeout, password, clientName, false, null, null, null, null);
  }

  public JedisClusterInfoCache(final GenericObjectPoolConfig poolConfig,
      final int connectionTimeout, final int soTimeout, final String password, final String clientName,
      boolean ssl, SSLSocketFactory sslSocketFactory, SSLParameters sslParameters, 
      HostnameVerifier hostnameVerifier, JedisClusterHostAndPortMap hostAndPortMap) {
    this.poolConfig = poolConfig;
    this.connectionTimeout = connectionTimeout;
    this.soTimeout = soTimeout;
    this.password = password;
    this.clientName = clientName;
    this.ssl = ssl;
    this.sslSocketFactory = sslSocketFactory;
    this.sslParameters = sslParameters;
    this.hostnameVerifier = hostnameVerifier;
    this.hostAndPortMap = hostAndPortMap;
  }

  public void discoverClusterNodesAndSlots(Jedis jedis) {
    //写锁保证只有一个连接进行初始化
    w.lock();

    try {
      //清空旧的节点和槽数据，并关闭节点连接
      reset();
      //使用"cluster slots"指令获取Cluster节点和槽的映射关系
      /**
       * 返回结构如下：
       * 127.0.0.1:6379> cluster slots
       * 1) 1) (integer) 5462    -- 起始槽
       *    2) (integer) 10922   -- 终止槽
       *    3) 1) "127.0.0.1"    -- 主节点IP
       *       2) (integer) 6380 -- 主节点端口
       *       3) "85371dd3c2c11dbb1cd506ed028de10bb7fa2816"  -- 主节点runId
       *    4) 1) "127.0.0.1"    -- 从节点IP
       *       2) (integer) 6383 -- 从节点端口
       *       3) "01740d2eb2c9f89014e2b8b673d444753d0685cd"  -- 从节点runId
       * 2) 1) (integer) 10923
       *    2) (integer) 16383
       *    3) 1) "127.0.0.1"
       *       2) (integer) 6381
       *       3) "aacad0a5a2b37d4e11f2253849263575adb78740"
       *    4) 1) "127.0.0.1"
       *       2) (integer) 6384
       *       3) "5e099198bba08597d695f6e2e3db2d6ec1494534"
       * 3) 1) (integer) 0
       *    2) (integer) 5461
       *    3) 1) "127.0.0.1"
       *       2) (integer) 6379
       *       3) "672cc8350bb1ac1d94e9c511ea234f6b7f86cab1"
       *    4) 1) "127.0.0.1"
       *       2) (integer) 6382
       *       3) "6bab67c3619c4b9cbdfd247ff3e446308a0c6326"
       */
      List<Object> slots = jedis.clusterSlots();

      for (Object slotInfoObj : slots) {
        List<Object> slotInfo = (List<Object>) slotInfoObj;

        if (slotInfo.size() <= MASTER_NODE_INDEX) {
          continue;
        }

        //解析出所负责的的所有槽
        List<Integer> slotNums = getAssignedSlotArray(slotInfo);

        // hostInfos
        /**
         *  解析主节点和从节点信息，构建槽和节点的映射关系：
         *  1. 添加nodes元素，key为"<ip>:<port>"，value为该节点对应的JedisPool连接池
         *  2. 添加slots元素，key为"slotNum"，value为该槽对应节点的JedisPool连接池
         */
        int size = slotInfo.size();
        for (int i = MASTER_NODE_INDEX; i < size; i++) {
          List<Object> hostInfos = (List<Object>) slotInfo.get(i);
          if (hostInfos.size() <= 0) {
            continue;
          }

          //获取节点IP端口信息
          HostAndPort targetNode = generateHostAndPort(hostInfos);
          //设置nodes，Map.Entry结构为<ip:port, JedisPool>
          //从节点和主节点都会进行保存
          setupNodeIfNotExist(targetNode);
          if (i == MASTER_NODE_INDEX) {
            //设置slots，Map.Entry结构为<slot, JedisPool>
            //只需要设置槽和主节点的映射关系
            assignSlotsToNode(slotNums, targetNode);
          }
        }
      }
    } finally {
      w.unlock();
    }
  }

  public void renewClusterSlots(Jedis jedis) {
    //If rediscovering is already in process - no need to start one more same rediscovering, just return
    if (!rediscovering) {
      try {
        w.lock();
        if (!rediscovering) {
          rediscovering = true;

          try {
            if (jedis != null) {
              try {
                discoverClusterSlots(jedis);
                return;
              } catch (JedisException e) {
                //try nodes from all pools
              }
            }

            for (JedisPool jp : getShuffledNodesPool()) {
              Jedis j = null;
              try {
                j = jp.getResource();
                discoverClusterSlots(j);
                return;
              } catch (JedisConnectionException e) {
                // try next nodes
              } finally {
                if (j != null) {
                  j.close();
                }
              }
            }
          } finally {
            rediscovering = false;      
          }
        }
      } finally {
        w.unlock();
      }
    }
  }

  private void discoverClusterSlots(Jedis jedis) {
    List<Object> slots = jedis.clusterSlots();
    this.slots.clear();

    for (Object slotInfoObj : slots) {
      List<Object> slotInfo = (List<Object>) slotInfoObj;

      if (slotInfo.size() <= MASTER_NODE_INDEX) {
        continue;
      }

      List<Integer> slotNums = getAssignedSlotArray(slotInfo);

      // hostInfos
      List<Object> hostInfos = (List<Object>) slotInfo.get(MASTER_NODE_INDEX);
      if (hostInfos.isEmpty()) {
        continue;
      }

      // at this time, we just use master, discard slave information
      HostAndPort targetNode = generateHostAndPort(hostInfos);
      assignSlotsToNode(slotNums, targetNode);
    }
  }

  private HostAndPort generateHostAndPort(List<Object> hostInfos) {
    String host = SafeEncoder.encode((byte[]) hostInfos.get(0));
    int port = ((Long) hostInfos.get(1)).intValue();
    if (ssl && hostAndPortMap != null) {
      HostAndPort hostAndPort = hostAndPortMap.getSSLHostAndPort(host, port);
      if (hostAndPort != null) {
        return hostAndPort;
      }
    }
    return new HostAndPort(host, port);
  }

  public JedisPool setupNodeIfNotExist(HostAndPort node) {
    w.lock();
    try {
      String nodeKey = getNodeKey(node);
      JedisPool existingPool = nodes.get(nodeKey);
      if (existingPool != null) return existingPool;

      JedisPool nodePool = new JedisPool(poolConfig, node.getHost(), node.getPort(),
          connectionTimeout, soTimeout, password, 0, clientName, 
          ssl, sslSocketFactory, sslParameters, hostnameVerifier);
      nodes.put(nodeKey, nodePool);
      return nodePool;
    } finally {
      w.unlock();
    }
  }

  public void assignSlotToNode(int slot, HostAndPort targetNode) {
    w.lock();
    try {
      JedisPool targetPool = setupNodeIfNotExist(targetNode);
      slots.put(slot, targetPool);
    } finally {
      w.unlock();
    }
  }

  public void assignSlotsToNode(List<Integer> targetSlots, HostAndPort targetNode) {
    w.lock();
    try {
      JedisPool targetPool = setupNodeIfNotExist(targetNode);
      for (Integer slot : targetSlots) {
        slots.put(slot, targetPool);
      }
    } finally {
      w.unlock();
    }
  }

  public JedisPool getNode(String nodeKey) {
    r.lock();
    try {
      return nodes.get(nodeKey);
    } finally {
      r.unlock();
    }
  }

  public JedisPool getSlotPool(int slot) {
    r.lock();
    try {
      return slots.get(slot);
    } finally {
      r.unlock();
    }
  }

  public Map<String, JedisPool> getNodes() {
    r.lock();
    try {
      return new HashMap<String, JedisPool>(nodes);
    } finally {
      r.unlock();
    }
  }

  public List<JedisPool> getShuffledNodesPool() {
    r.lock();
    try {
      List<JedisPool> pools = new ArrayList<JedisPool>(nodes.values());
      Collections.shuffle(pools);
      return pools;
    } finally {
      r.unlock();
    }
  }

  /**
   * Clear discovered nodes collections and gently release allocated resources
   */
  public void reset() {
    w.lock();
    try {
      for (JedisPool pool : nodes.values()) {
        try {
          if (pool != null) {
            pool.destroy();
          }
        } catch (Exception e) {
          // pass
        }
      }
      nodes.clear();
      slots.clear();
    } finally {
      w.unlock();
    }
  }

  public static String getNodeKey(HostAndPort hnp) {
    return hnp.getHost() + ":" + hnp.getPort();
  }

  public static String getNodeKey(Client client) {
    return client.getHost() + ":" + client.getPort();
  }

  public static String getNodeKey(Jedis jedis) {
    return getNodeKey(jedis.getClient());
  }

  private List<Integer> getAssignedSlotArray(List<Object> slotInfo) {
    List<Integer> slotNums = new ArrayList<Integer>();
    for (int slot = ((Long) slotInfo.get(0)).intValue(); slot <= ((Long) slotInfo.get(1))
        .intValue(); slot++) {
      slotNums.add(slot);
    }
    return slotNums;
  }
}
