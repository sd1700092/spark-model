package portrait

import com.hankcs.hanlp.HanLP
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

import scala.util.control.Breaks

/**
  * 作者：blogchong
  * 公众号: 数据虫巢(ID:blogchong)
  * 交流微信：mute88
  * Desc: 基于movies数据集，计算用户的兴趣元素画像标签
  */
object moviesPortraitApp {

  case class UserRatingMovie(userId: String, movieId: String, rate: Double, timestamp: Long)
  case class UserMovieGenre(userId: String, movie: String, genre: String)
  case class UserMovieTag(userId: String, movieId: String, tags: String, timestamp: Long)

  def main(args: Array[String]): Unit = {

    //设置hive访问host以及端口  hadoop9
    //    val HIVE_METASTORE_URIS = "thrift://139.199.162.36:9083"
    //注意：实际使用的时候，替换成自己服务器集群中，hive的host以及访问端口
    //    val HIVE_METASTORE_URIS = "thrift://hive-host-01:9083,thrift://hive-host-02:9083"
    //    System.setProperty("hive.metastore.uris", HIVE_METASTORE_URIS)


    //构建一个通用的sparkSession
    val sparkSession = SparkSession
      .builder()
      .appName("portrait_m")
      .config("spark.master", "local[4]")
      //      .enableHiveSupport()
      .getOrCreate()

    val sc: SparkContext = sparkSession.sparkContext
    val ratingsRDD: RDD[Array[String]] = sc.textFile("D:\\Download\\ratings_tmp.dat").map(line => line.split("\t"))/*.sample(false, 0.1, System.currentTimeMillis())*/
    val tagsRDD: RDD[Array[String]] = sc.textFile("D:\\Download\\tags_tmp.dat").map(line => line.split("\t"))/*.sample(false, 0.1, System.currentTimeMillis())*/
    val moviesRDD: RDD[Array[String]] = sc.textFile("D:\\Download\\movies_tmp.dat").map(line => line.split("\t"))/*.sample(false, 0.1, System.currentTimeMillis())*/
    import sparkSession.implicits._

    val ratingsDF = ratingsRDD.map(arr => UserRatingMovie(arr(0), arr(1), arr(2).toDouble, arr(3).toLong)).toDF()
    ratingsDF.createOrReplaceTempView("mite_ratings")
    val moviesDF: DataFrame = moviesRDD.map(arr => UserMovieGenre(arr(0), arr(1), arr(2))).toDF()
    moviesDF.createOrReplaceTempView("mite_movies")
    val tagsDF: DataFrame = tagsRDD.map(arr => UserMovieTag(arr(0), arr(1), arr(2), arr(3).toLong)).toDF()
    tagsDF.createOrReplaceTempView("mite_tags")

    //获取rating评分数据集
    val ratingData = sparkSession.sql("select userid,movieid,rate  from mite_ratings")
    ratingData.show(15)

    //获取movies电影数据集
    val moviesData = sparkSession.sql("select userid,movie,genre  from mite_movies")
    moviesData.show(15)

    //获取电影tags数据
    val tagsData = sparkSession.sql("select movieid,tags from mite_tags").rdd
    tagsData.take(15).foreach(println)

    System.out.println("=================001 GET DATA===========================")

    //进行tags标签的处理,包括分词，去除停用词等等
    val tagsStandardize = tagsData.map{
      f =>
        val movieid = f.get(0)
        //进行逻辑判断，size>3的进行标准化处理
        val tag = if (f.get(1).toString.split(" ").size <= 3) {
          f.get(1)
        }else{
          //进行主题词抽取(能屏蔽掉停用词)
          HanLP.extractKeyword(f.get(1).toString, 20).toArray.toSet.mkString(" ")
        }
        (movieid,tag)
    }

    System.out.println("=================002 HANLP===========================")

    //进行相似tag合并操作，最终返回依然是(movieid,tag)集合，但tag会做预处理
    val tagsStandardizeTmp = tagsStandardize.collect()
    val tagsSimi = tagsStandardize.map{
      f=>
        var retTag = f._2
        if (f._2.toString.split(" ").size == 1) {
          var simiTmp = ""

          val tagsTmpStand = tagsStandardizeTmp
                        .filter(_._2.toString.split(" ").size != 1 )
                        .filter(f._2.toString.size < _._2.toString.size)
                        .sortBy(_._2.toString.size)

          var x = 0
          val loop = new Breaks

          tagsTmpStand.map{
            tagTmp=>
              val flag = getEditSize(f._2.toString,tagTmp._2.toString)
              if (flag == 1){
                retTag = tagTmp._2
                loop.break()
              }
          }

          (f._1,retTag)
        } else {
          f
        }
    }.map(f=>Row(f._1,f._2.toString.trim))
    tagsSimi.take(300).foreach(println)

    System.out.println("=================003 SIMI===========================")

    //先将预处理之后的movie-tag数据进行dataframe
//    val schemaString = "movieid tag"
//    val schema = StructType(schemaString.split(" ").map(fieldName=>StructField(fieldName,StringType,true)))
    //DataFrame格式化申明
    val schemaString = "movieid tag"
    val schema = StructType(schemaString.split(" ").map(fieldName => StructField(fieldName, StringType, true)))
    val tagsSimiDataFrame = sparkSession.createDataFrame(tagsSimi,schema)

    //对rating(userid,movieid,rate)，tags(movieid,tag)进行join，以movieid关联
    //join步骤：将(userId, movieId, rate)与(movieId, tag)按照movieId字段进行连接
    val tagRateDataFrame = ratingData.join(tagsSimiDataFrame,ratingData("movieid")===tagsSimiDataFrame("movieid"),"inner").select("userid","tag","rate")
    tagRateDataFrame.show(50)

    System.out.println("=================004 JOIN===========================")

    //reduce步骤：将(userId, tag, rate)中(userId, tag)相同的分数rate相加
    val tagSumRateDataFrame = tagRateDataFrame.groupBy("userid","tag").sum("rate")

    System.out.println("=================005 REDUCE===========================")

    //最终数据重新按userid升序,tag的rate的降序排序
    val userTags = tagSumRateDataFrame.orderBy(tagSumRateDataFrame("userid"),tagSumRateDataFrame("sum(rate)").desc)
    userTags.show(300)
    System.out.println("=================006 SORT===========================")

    /*//将结果存入hive
    val userTagTmpTableName = "mite_portraittmp"
    val userTagTableName = "mite8.mite_portrait"

    userTags.createOrReplaceTempView(userTagTmpTableName)
    sparkSession.sql("insert into table " + userTagTableName + " select * from " + userTagTmpTableName)*/

    System.out.println("=================007 SAVE===========================")


    sparkSession.close()
  }

  //合并tag，合并原则：长度=1(单个词)；前缀相似度>=2/7，
  def getEditSize(str1: String, str2: String): Int = {
    if (str2.size > str1.size) {
      0
    } else {
      //计数器
      var count = 0
      val loop = new Breaks
      //以较短的str2进行遍历，并逐个比较
      val lengthStr2 = str2.getBytes().length
      var i = 0
      for (i <- 1 to lengthStr2) {
        if (str2.getBytes()(i) == str1.getBytes()(i)) {
          //逐个匹配字节，相等则计数器+1
          count += 1
        } else {
          //一旦出现前缀不一致则中断循环，开始计算重叠度
          loop.break()
        }
      }

      //计算重叠度,当前缀重叠度大于等于2/7时，进行合并
      if (count.asInstanceOf[Double] / str1.getBytes().size.asInstanceOf[Double] >= (1 - 0.286)) {
        1
      } else {
        0
      }
    }
  }

}
