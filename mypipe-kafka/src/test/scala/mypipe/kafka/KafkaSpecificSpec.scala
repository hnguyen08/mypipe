package mypipe.kafka

import com.typesafe.config.ConfigFactory
import mypipe._
import mypipe.api.Conf
import mypipe.api.event.Mutation
import mypipe.api.repo.FileBasedBinaryLogPositionRepository
import mypipe.avro.{ AvroVersionedRecordDeserializer, InMemorySchemaRepo }
import mypipe.avro.schema.{ AvroSchema, AvroSchemaUtils, GenericSchemaRepository, ShortSchemaId }
import mypipe.kafka.consumer.{ KafkaMutationAvroConsumer, KafkaSpecificAvroDecoder }
import mypipe.mysql.MySQLBinaryLogConsumer
import mypipe.pipe.Pipe
import mypipe.kafka.producer.KafkaMutationSpecificAvroProducer
import org.apache.avro.Schema
import org.scalatest.BeforeAndAfterAll
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.reflect.runtime.universe._

class KafkaSpecificSpec extends UnitSpec with DatabaseSpec with ActorSystemSpec with BeforeAndAfterAll {

  val log = LoggerFactory.getLogger(getClass)

  @volatile var done = false

  val kafkaProducer = new KafkaMutationSpecificAvroProducer(
    conf.getConfig("mypipe.test.kafka-specific-producer"))

  val c = ConfigFactory.parseString(
    s"""
         |{
         |  source = "${Queries.DATABASE.host}:${Queries.DATABASE.port}:${Queries.DATABASE.username}:${Queries.DATABASE.password}"
         |}
         """.stripMargin)
  val binlogConsumer = MySQLBinaryLogConsumer(c)
  val binlogPosRepo = new FileBasedBinaryLogPositionRepository(filePrefix = "test-pipe-kafka-specific", dataDir = Conf.DATADIR)
  val pipe = new Pipe("test-pipe-kafka-specific", binlogConsumer, kafkaProducer, binlogPosRepo)

  override def beforeAll() {
    pipe.connect()
    super.beforeAll()
    while (!pipe.isConnected) { Thread.sleep(10) }
  }

  override def afterAll() {
    pipe.disconnect()
    super.afterAll()
  }

  "A specific Kafka Avro producer and consumer" should "properly produce and consume insert, update, and delete events" in withDatabase { db ⇒

    val DATABASE = Queries.DATABASE.name
    val TABLE = Queries.TABLE.name
    val USERNAME = Queries.INSERT.username
    val USERNAME2 = Queries.UPDATE.username
    val BIO = Queries.INSERT.bio
    val BIO2 = Queries.UPDATE.bio
    val LOGIN_COUNT = 5
    val zkConnect = conf.getString("mypipe.test.kafka-specific-producer.zk-connect")

    val kafkaConsumer = new KafkaMutationAvroConsumer[mypipe.kafka.UserInsert, mypipe.kafka.UserUpdate, mypipe.kafka.UserDelete](
      topic = KafkaUtil.specificTopic(DATABASE, TABLE),
      zkConnect = zkConnect,
      groupId = s"${DATABASE}_${TABLE}_specific_test-${System.currentTimeMillis()}",
      valueDecoder = KafkaSpecificAvroDecoder[mypipe.kafka.UserInsert, mypipe.kafka.UserUpdate, mypipe.kafka.UserDelete](DATABASE, TABLE, TestSchemaRepo))(

      insertCallback = { insertMutation ⇒
        log.debug("consumed insert mutation: " + insertMutation)
        try {
          assert(insertMutation.getDatabase.toString == DATABASE)
          assert(insertMutation.getTable.toString == TABLE)
          assert(insertMutation.getUsername.toString == USERNAME)
          assert(insertMutation.getLoginCount == LOGIN_COUNT)
          assert(new String(insertMutation.getBio.array) == BIO)
        } catch {
          case e: Exception ⇒ log.error(s"Failed testing insert: ${e.getMessage}: ${e.getStackTrace.mkString(System.lineSeparator())}")
        }

        true
      },

      updateCallback = { updateMutation ⇒
        log.debug("consumed update mutation: " + updateMutation)
        try {
          assert(updateMutation.getDatabase.toString == DATABASE)
          assert(updateMutation.getTable.toString == TABLE)
          assert(updateMutation.getOldUsername.toString == USERNAME)
          assert(updateMutation.getNewUsername.toString == USERNAME2)
          assert(new String(updateMutation.getOldBio.array) == BIO)
          assert(new String(updateMutation.getNewBio.array) == BIO2)
          assert(updateMutation.getOldLoginCount == LOGIN_COUNT)
          assert(updateMutation.getNewLoginCount == LOGIN_COUNT + 1)
        } catch {
          case e: Exception ⇒ log.error(s"Failed testing update: ${e.getMessage}: ${e.getStackTrace.mkString(System.lineSeparator())}")
        }

        true
      },

      deleteCallback = { deleteMutation ⇒
        log.debug("consumed delete mutation: " + deleteMutation)
        try {
          assert(deleteMutation.getDatabase.toString == DATABASE)
          assert(deleteMutation.getTable.toString == TABLE)
          assert(deleteMutation.getUsername.toString == USERNAME2)
          assert(deleteMutation.getLoginCount == LOGIN_COUNT + 1)
        } catch {
          case e: Exception ⇒ log.error(s"Failed testing delete: ${e.getMessage}: ${e.getStackTrace.mkString(System.lineSeparator())}")
        }

        done = true
        true
      },

      implicitly[TypeTag[UserInsert]],
      implicitly[TypeTag[UserUpdate]],
      implicitly[TypeTag[UserDelete]])

    val future = kafkaConsumer.start

    Await.result(db.connection.sendQuery(Queries.INSERT.statement(loginCount = LOGIN_COUNT)), 2.seconds)
    Await.result(db.connection.sendQuery(Queries.UPDATE.statement), 2.seconds)
    Await.result(db.connection.sendQuery(Queries.DELETE.statement), 2.seconds)
    Await.result(Future { while (!done) Thread.sleep(100) }, 20.seconds)

    try {
      kafkaConsumer.stop
      Await.result(future, 5.seconds)
    } catch {
      case e: Exception ⇒ log.error(s"Failed stopping consumer: ${e.getMessage}: ${e.getStackTrace.mkString(System.lineSeparator())}")
    }

    if (!done) assert(false)
  }
}

object TestSchemaRepo extends InMemorySchemaRepo[Short, Schema] with ShortSchemaId with AvroSchema {
  val DATABASE = "mypipe"
  val TABLE = "user"
  val insertSchemaId = registerSchema(AvroSchemaUtils.specificSubject(DATABASE, TABLE, Mutation.InsertString), new UserInsert().getSchema)
  val updateSchemaId = registerSchema(AvroSchemaUtils.specificSubject(DATABASE, TABLE, Mutation.UpdateString), new UserUpdate().getSchema)
  val deleteSchemaId = registerSchema(AvroSchemaUtils.specificSubject(DATABASE, TABLE, Mutation.DeleteString), new UserDelete().getSchema)
}