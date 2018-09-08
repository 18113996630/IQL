package cn.i4.iql

import java.io.{ByteArrayOutputStream, PrintStream}
import java.lang.reflect.Modifier
import java.sql.Timestamp
import cn.i4.iql.ExeActor._

import akka.actor.{Actor, Props}
import cn.i4.iql.antlr.{IQLLexer, IQLListener, IQLParser}
import cn.i4.iql.domain.Bean._
import cn.i4.iql.utils.{BatchSQLRunnerEngine, PropsUtils, ZkUtils}
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.{ANTLRInputStream, CommonTokenStream}
import org.apache.spark.sql.SparkSession
import cn.i4.iql.repl.SparkInterpreter
import com.alibaba.fastjson.{JSON, JSONArray, JSONObject}
import cn.i4.iql.IqlService._
import cn.i4.iql.repl.Interpreter._
import org.apache.spark.SparkConf
import org.apache.spark.sql.bridge.SparkBridge


class ExeActor(_interpreter: SparkInterpreter, iqlSession: IQLSession) extends Actor with Logging {

    var sparkSession: SparkSession = _
    var interpreter: SparkInterpreter = _
    var resJson = new JSONObject()
    val zkValidActorPath = ZkUtils.validEnginePath + "/" + iqlSession.engineInfo + "_" + context.self.path.name

    override def preStart(): Unit = {
        warn("Actor Start ...")
        interpreter = _interpreter
        sparkSession = IqlService.createSpark(new SparkConf()).newSession()
        registerUDF("cn.i4.iql.utils.SparkUDF")
        ZkUtils.registerActorInEngine(ZkUtils.getZkClient(PropsUtils.get("zkServers")), zkValidActorPath, "", 6000, -1)
    }

    override def postStop(): Unit = {
        warn("Actor Stop ...")
        interpreter.close()
        sparkSession.stop()
    }

    override def receive: Receive = {

        case HiveCatalog() =>
            sender() ! getHiveCatalog

        case HiveCatalogWithAutoComplete() =>
            sender() ! getHiveCatalogWithAutoComplete

        case Iql(mode, iql, variables) =>
            actorWapper() { () => {
                var rIql = iql
                val variblesIters = JSON.parseArray(variables).iterator()
                while (variblesIters.hasNext) {
                    val nObject = JSON.parseObject(variblesIters.next().toString)
                    rIql = rIql.replace("${" + nObject.getString("name") + "}", nObject.getString("value"))
                }
                schedulerMode = !schedulerMode //切换调度池
                sparkSession.sparkContext.setLocalProperty("spark.scheduler.pool", if (schedulerMode) "pool_fair_1" else "pool_fair_2")
                resJson = new JSONObject()
                resJson.put("startTime", new Timestamp(System.currentTimeMillis))
                resJson.put("iql", iql)
                resJson.put("variables", variables)
                //为当前iql设置groupId
                val groupId = BatchSQLRunnerEngine.getGroupId
                resJson.put("engineInfoAndGroupId", iqlSession.engineInfo + "_" + groupId)
                sparkSession.sparkContext.clearJobGroup()
                sparkSession.sparkContext.setJobDescription("iql:" + rIql)
                sparkSession.sparkContext.setJobGroup("iqlid:" + groupId, "iql:" + rIql)
                //将该iql任务的唯一标识返回
                sender() ! resJson.toJSONString

                mode match {
                    case "iql" =>
                        resJson.put("mode", "iql")
                        parseSQL(rIql, new IQLSQLExecListener(sparkSession, iqlSession))
                    case "code" =>
                        resJson.put("mode", "code")
                        rIql = rIql.replaceAll("'", "\"").replaceAll("\n", " ")
                        val response = interpreter.execute(rIql)
                        getExecuteState(response)
                    case _ =>
                }
            }
            }

        case GetBatchResult(engineInfoAndGroupId) =>
            if (iqlSession.batchJob.keySet().contains(engineInfoAndGroupId)) {
                sender() ! iqlSession.batchJob.get(engineInfoAndGroupId)
                iqlSession.batchJob.remove(engineInfoAndGroupId)
            } else {
                sender() ! "{'status':'RUNNING'}"
            }

        case GetActiveStream() =>
            sender() ! iqlSession.streamJob.filter(_._2.isActive).keys.foldLeft(new JSONArray()) {
                case (array, stream) =>
                    stream.split("_") match {
                        case Array(engineInfo, name, uid) =>
                            val obj = new JSONObject()
                            obj.put("engineInfo", engineInfo)
                            obj.put("name", name)
                            obj.put("uid", uid)
                            array.add(obj)
                    }
                    array
            }.toJSONString

        case StreamJobStatus(name) =>
            sender() ! iqlSession.streamJob(name).status.prettyJson

        case StopSreamJob(name) =>
            iqlSession.streamJob(name).stop()
            sender() ! !iqlSession.streamJob(name).isActive

        case CancelJob(groupId) =>
            sparkSession.sparkContext.cancelJobGroup("iqlid:" + groupId)
            sender() ! true

        case StopIQL() => context.system.terminate()

        case _ => None
    }

    // antlr4解析SQL语句
    def parseSQL(input: String, listener: IQLSQLExecListener): Unit = {
        try {
            parse(input,listener)
            val endTime = System.currentTimeMillis()
            val take = (endTime - resJson.getTimestamp("startTime").getTime) / 1000
            resJson.put("hdfsPath", listener.getResult("hdfsPath"))
            resJson.put("schema", listener.getResult("schema"))
            resJson.put("takeTime", take)
            resJson.put("isSuccess", true)
        } catch {
            case e: Exception =>
                e.printStackTrace()
                resJson.put("isSuccess", false)
                val out = new ByteArrayOutputStream()
                e.printStackTrace(new PrintStream(out))
                resJson.put("errorMessage", new String(out.toByteArray))
                out.close()
        }
        resJson.put("status", "FINISH")
        iqlSession.batchJob.put(resJson.getString("engineInfoAndGroupId"), resJson.toJSONString)
    }

    // 执行前从zk中删除当前对应节点（标记不可用），执行后往zk中写入可用节点（标记可用）
    def actorWapper()(f: () => Unit) {
        ZkUtils.deletePath(ZkUtils.getZkClient(PropsUtils.get("zkServers")), zkValidActorPath)
        try {
            f()
        } catch {
            case e: Exception =>
                resJson.put("isSuccess", false)
                val out = new ByteArrayOutputStream()
                e.printStackTrace(new PrintStream(out))
                resJson.put("errorMessage", new String(out.toByteArray))
                sender() ! resJson.toJSONString
        }
        ZkUtils.registerActorInEngine(ZkUtils.getZkClient(PropsUtils.get("zkServers")), zkValidActorPath, "", 6000, -1)
    }

    // 获取hive元数据信息
    def getHiveCatalog: String = {
        val hiveArray = new JSONArray()
        var num: Int = 0
        SparkBridge.getHiveCatalg(sparkSession).client.listDatabases("*").foreach(db => {
            num += 1
            val dbId = num
            val dbObj = new JSONObject()
            dbObj.put("id", dbId)
            dbObj.put("name",db)
            dbObj.put("pId", 0)
            hiveArray.add(dbObj)
            SparkBridge.getHiveCatalg(sparkSession).client.listTables(db).foreach(tb => {
                num += 1
                val tbId = num
                val tbObj = new JSONObject()
                tbObj.put("id", tbId)
                tbObj.put("pId", dbId)
                tbObj.put("name",tb)
                hiveArray.add(tbObj)
                SparkBridge.getHiveCatalg(sparkSession).client.getTable(db, tb).schema.fields.foreach(f => {
                    num += 1
                    val fieldId = num
                    val fieldObj = new JSONObject()
                    fieldObj.put("id", fieldId)
                    fieldObj.put("pId", tbId)
                    fieldObj.put("name", f.name + "(" + f.dataType.typeName + ")")
                    hiveArray.add(fieldObj)
                }
                )
            })
        })
        hiveArray.toJSONString
    }

    def getHiveCatalogWithAutoComplete: String = {
        val hiveObj = new JSONObject()
        SparkBridge.getHiveCatalg(sparkSession).client.listDatabases("*").foreach(db => {
            val tbArray = new JSONArray()
            SparkBridge.getHiveCatalg(sparkSession).client.listTables(db).foreach(tb => {
                tbArray.add(tb)
                val cloArray = new JSONArray()
                SparkBridge.getHiveCatalg(sparkSession).client.getTable(db, tb).schema.fields.foreach(f => cloArray.add(f.name))
                hiveObj.put(tb, cloArray)
            })
            hiveObj.put(db, tbArray)
        })
        hiveObj.toJSONString
    }

    def getExecuteState(response: ExecuteResponse): Unit = {
        response match {
            case _: ExecuteIncomplete => getExecuteState(response)
            case e: ExecuteSuccess =>
                val take = (System.currentTimeMillis() - resJson.getTimestamp("startTime").getTime) / 1000
                resJson.put("takeTime", take)
                resJson.put("isSuccess", true)
                resJson.put("content", e.content.values.values.mkString("\n"))
                resJson.put("status", "FINISH")
                iqlSession.batchJob.put(resJson.getString("engineInfoAndGroupId"), resJson.toJSONString)
            case e: ExecuteError =>
                resJson.put("isSuccess", false)
                resJson.put("errorMessage", e.evalue)
                resJson.put("status", "FINISH")
                iqlSession.batchJob.put(resJson.getString("engineInfoAndGroupId"), resJson.toJSONString)
            case e: ExecuteAborted =>
                resJson.put("isSuccess", false)
                resJson.put("errorMessage", e.message)
                resJson.put("status", "FINISH")
                iqlSession.batchJob.put(resJson.getString("engineInfoAndGroupId"), resJson.toJSONString)
            case _ =>
        }
    }

    def registerUDF(clazz: String) = {
        Class.forName(clazz).getMethods.foreach { f =>
            try {
                if (Modifier.isStatic(f.getModifiers)) {
                    f.invoke(null, sparkSession)
                }
            } catch {
                case e:Exception => e.printStackTrace()
            }
        }
    }
}

object ExeActor {

    def props(interpreter: SparkInterpreter, iqlSession: IQLSession): Props = Props(new ExeActor(interpreter, iqlSession))

    // antlr4解析SQL语句
    def parse(input: String, listener: IQLSQLExecListener): Unit = {
            warn("\n" + ("*" * 80) + "\n" + input + "\n" + ("*" * 80))
            val loadLexer = new IQLLexer(new ANTLRInputStream(input))
            val tokens = new CommonTokenStream(loadLexer)
            val parser = new IQLParser(tokens)
            val stat = parser.statement()
            ParseTreeWalker.DEFAULT.walk(listener, stat)
    }


}
