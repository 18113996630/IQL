## IQL
 
![](https://upload-images.jianshu.io/upload_images/3597066-e19cdef507fd77a7.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

基于SparkSQL实现了一套即席查询服务，具有如下特性：
- 优雅的交互方式，支持多种datasource/sink，多数据源混算
- spark常驻服务，基于zookeeper的引擎自动发现
- 负载均衡，多个引擎随机执行
- 多session模式实现并行查询
- 采用spark的FAIR调度，避免资源被大任务独占
- 基于spark的动态资源分配，在无任务的情况下不会占用executor资源
- 支持Cluster和Client模式启动
- 基于Structured Streaming实现SQL动态添加流
- 基于REPL的写代码功能，动态注册UDF函数
- 高效的script管理，配合import/include语法完成各script的关联
- 对数据源操作的权限验证

支持的数据源：hdfs、hive、hbase、kafka、mysql、es

支持的文件格式：parquet、csv、orc、json、text

在Structured Streaming支持的Sink之外还增加了对Hbase、MySQL、es的支持



## [Quickstart](https://github.com/teeyog/IQL/blob/master/docs/quick-start.md)
 
### HBase
 
#### 加载数据

使用方式：

```
load hbase.t_mbl_user_version_info 
where `spark.table.schema`="userid:String,osversion:String,toolversion:String"
	   and `hbase.table.schema`=":rowkey,info:osversion,info:toolversion" 
	   and `hbase.zookeeper.quorum`="localhost:2181"
as tb;
```

| 参数 | 说明 | 默认值 | 
| ------------- |:-------------:|:-------------:|
| hbase.zookeeper.quorum | zookeeper地址| localhost:2181|
| spark.table.schema | Spark临时表对应的schema（eg: "ID:String,appname:String,age:Int"）| 无 |
| hbase.table.schema| HBase表对应schema（eg: ":rowkey,info:appname,info:age"）| 无 |
|spark.rowkey.view.name| rowkey对应的dataframe创建的temp view名 ，设置了该值后只获取rowkey对应的数据  |  无 |


#### 保存数据

使用方式：

```
save tb1 as hbase.tableName 
where `hbase.zookeeper.quorum`="localhost:2181"
      and `hbase.table.rowkey.filed`="name"
```

| 参数 | 说明 | 默认值 | 
| ------------- |:-------------:|:-------------:|
|hbase.zookeeper.quorum | zookeeper地址| localhost:2181|
|hbase.table.rowkey.field | spark临时表中作为hbase的rowkey的字段名| 第一个字段 |
|bulkload.enable| 是否启动bulkload| false|
|hbase.table.name | Hbase表名  |  无 |
|hbase.table.family | 列族名  |  info |
|hbase.table.region.splits | 预分区分区段，以数组字符串方式指定，如 ['1','2','3']  |  无 |
|hbase.table.rowkey.prefix | 当rowkey是数字，预分区需要指明前缀的formate形式，如 00，在startKey和endKey都未设置的情况下会生成00-99等100个分区  |  无 |
|hbase.table.startKey | 预分区开始key，当hbase表不存在时，会自动创建Hbase表，不带一下三个参数则只有一个分区 |  无 |
|hbase.table.endKey | 预分区结束key  |  无 |
|hbase.table.numReg | 分区个数 |  无 |
|hbase.check_table | 写入hbase表时，是否需要检查表是否存在  |  false |
|hbase.cf.ttl | ttl | 无 |

### MySQL
- 加载数据
```
load jdbc.ai_log_count 
where driver="com.mysql.jdbc.Driver" // 默认
      and url="jdbc:mysql://localhost/db?characterEncoding=utf8" 
      and user="root" // 默认
      and password="***" //默认
as tb; 
```

- 保存数据
```
save append tb as jdbc.aatest_delete;
```

### 文件操作 (其中formate可为：json、orc、csv、parquet、text)
- 加载数据
 ```
load format.`path` as tb;
```

- 保存数据
```
save tb as formate.`path` partitionBy uid coalesce 2;
```

### Kafka

 ```
load kafka.`topicName`
where maxRatePerPartition="200"
	and `group.id`="consumerGroupId"
```

| 参数 | 说明 | 默认值 | 
| ------------- |:-------------:|:-------------:|
| spark.job.mode | 任务模式（batch:离线任务，stream:实时任务）| batch |
| mail.receiver | 任务失败邮件通知（多个邮箱逗号分隔）| 无 |
| sendDingDingOnTerminated | 钉钉Robot通知 | false |

### 动态注册UDF函数
```
register udf.`myupper`
where func="
	def apply(name:String)={
		name.toUpperCase
	}
";

load jsonStr.'
{"name":"ufo"}
{"name":"uu"}
{"name":"HIN"}
' as tb1;

select myupper(name) as newName from tb1;
```

### include(import等效)语法，通过路径引入脚本片段

![import语法](https://upload-images.jianshu.io/upload_images/3597066-cf42197b02fbaa5c.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)



### 参考
[StreamingPro之MLSQL](https://github.com/allwefantasy/streamingpro)

[spark sql在喜马拉雅的使用之xql](https://github.com/cjuexuan/mynote/issues/21)

