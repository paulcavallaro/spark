package spark

import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet

import akka.actor.Actor
import akka.actor.Actor._
import akka.actor.ActorRef

sealed trait CacheTrackerMessage
case class AddedToCache(rddId: Int, partition: Int, host: String) extends CacheTrackerMessage
case class DroppedFromCache(rddId: Int, partition: Int, host: String) extends CacheTrackerMessage
case class MemoryCacheLost(host: String) extends CacheTrackerMessage
case class RegisterRDD(rddId: Int, numPartitions: Int) extends CacheTrackerMessage
case object GetCacheLocations extends CacheTrackerMessage
case object StopCacheTracker extends CacheTrackerMessage

class CacheTrackerActor extends DaemonActor with Logging {
  val locs = new HashMap[Int, Array[List[String]]]
  // TODO: Should probably store (String, CacheType) tuples
  
  def receive = {
    case RegisterRDD(rddId: Int, numPartitions: Int) =>
      logInfo("Registering RDD " + rddId + " with " + numPartitions + " partitions")
      locs(rddId) = Array.fill[List[String]](numPartitions)(Nil)
      self.reply('OK)
    
    case AddedToCache(rddId, partition, host) =>
      logInfo("Cache entry added: (%s, %s) on %s".format(rddId, partition, host))
      locs(rddId)(partition) = host :: locs(rddId)(partition)
      self.reply('OK)
      
    case DroppedFromCache(rddId, partition, host) =>
      logInfo("Cache entry removed: (%s, %s) on %s".format(rddId, partition, host))
      locs(rddId)(partition) = locs(rddId)(partition).filterNot(_ == host)
    
    case MemoryCacheLost(host) =>
      logInfo("Memory cache lost on " + host)
      // TODO: Drop host from the memory locations list of all RDDs
    
    case GetCacheLocations =>
      logInfo("Asked for current cache locations")
      val locsCopy = new HashMap[Int, Array[List[String]]]
      for ((rddId, array) <- locs) {
        locsCopy(rddId) = array.clone()
      }
      self.reply(locsCopy)

    case StopCacheTracker =>
      self.reply('OK)
      self.exit()
  }
}

class CacheTracker(isMaster: Boolean, theCache: Cache) extends Logging {
  // Tracker actor on the master, or remote reference to it on workers
  var trackerActor: ActorRef = null
  
  if (isMaster) {
    val actor = actorOf(new CacheTrackerActor)
    actor.start()
    trackerActor = actor
    remote.register("CacheTracker", actor)
  } else {
    val host = System.getProperty("spark.master.host")
    val port = System.getProperty("spark.master.port").toInt
    trackerActor = remote.actorFor("CacheTracker", host, port)
  }

  val registeredRddIds = new HashSet[Int]

  // Stores map results for various splits locally
  val cache = theCache.newKeySpace()

  // Remembers which splits are currently being loaded (on worker nodes)
  val loading = new HashSet[(Int, Int)]
  
  // Registers an RDD (on master only)
  def registerRDD(rddId: Int, numPartitions: Int) {
    registeredRddIds.synchronized {
      if (!registeredRddIds.contains(rddId)) {
        logInfo("Registering RDD ID " + rddId + " with cache")
        registeredRddIds += rddId
        (trackerActor ? RegisterRDD(rddId, numPartitions)).get
      }
    }
  }
  
  // Get a snapshot of the currently known locations
  def getLocationsSnapshot(): HashMap[Int, Array[List[String]]] = {
    (trackerActor ? GetCacheLocations).as[HashMap[Int, Array[List[String]]]].get
  }
  
  // Gets or computes an RDD split
  def getOrCompute[T](rdd: RDD[T], split: Split)(implicit m: ClassManifest[T]): Iterator[T] = {
    val key = (rdd.id, split.index)
    logInfo("CachedRDD partition key is " + key)
    val cachedVal = cache.get(key)
    if (cachedVal != null) {
      // Split is in cache, so just return its values
      logInfo("Found partition in cache!")
      return cachedVal.asInstanceOf[Array[T]].iterator
    } else {
      // Mark the split as loading (unless someone else marks it first)
      loading.synchronized {
        if (loading.contains(key)) {
          while (loading.contains(key)) {
            try {loading.wait()} catch {case _ =>}
          }
          return cache.get(key).asInstanceOf[Array[T]].iterator
        } else {
          loading.add(key)
        }
      }
      // If we got here, we have to load the split
      // Tell the master that we're doing so
      val host = System.getProperty("spark.hostname", Utils.localHostName)
      val future = trackerActor ? AddedToCache(rdd.id, split.index, host)
      // TODO: fetch any remote copy of the split that may be available
      // TODO: also register a listener for when it unloads
      logInfo("Computing partition " + split)
      val array = rdd.compute(split).toArray(m)
      cache.put(key, array)
      loading.synchronized {
        loading.remove(key)
        loading.notifyAll()
      }
      future.get // Wait for the reply from the cache tracker
      return array.iterator
    }
  }

  // Reports that an entry has been dropped from the cache
  def dropEntry(key: Any) {
    key match {
      case (keySpaceId: Long, (rddId: Int, partition: Int)) =>
        val host = System.getProperty("spark.hostname", Utils.localHostName)
        trackerActor !! DroppedFromCache(rddId, partition, host)
      case _ =>
        logWarning("Unknown key format: %s".format(key))
    }
  }

  def stop() {
    (trackerActor ? StopCacheTracker).get
    registeredRddIds.clear()
    trackerActor = null
  }
}
