package code.bankconnectors.storedprocedure

import java.sql.PreparedStatement

import code.api.util.APIUtil
import com.openbankproject.commons.model.TopicTrait
import net.liftweb.json
import net.liftweb.json.JValue
import net.liftweb.json.Serialization.write
import scalikejdbc.{DB, _}

/**
 * Stored procedure utils.
 * The reason of extract this util: if not call stored procedure connector method, the db connection of
 * stored procedure will not be initialized.
 */
object StoredProcedureUtils {
  private implicit val formats = code.api.util.CustomJsonFormats.formats
  private val before: PreparedStatement => Unit = _ => ()

  // lazy initial DB connection
  {
    val driver = APIUtil.getPropsValue("stored_procedure_connector.driver").openOrThrowException("mandatory property stored_procedure_connector.driver is missing!")
    val url = APIUtil.getPropsValue("stored_procedure_connector.url").openOrThrowException("mandatory property stored_procedure_connector.url is missing!")
    val user = APIUtil.getPropsValue("stored_procedure_connector.user").openOrThrowException("mandatory property stored_procedure_connector.user is missing!")
    val password = APIUtil.getPropsValue("stored_procedure_connector.password").openOrThrowException("mandatory property stored_procedure_connector.password is missing!")

    val initialSize = APIUtil.getPropsAsIntValue("stored_procedure_connector.poolInitialSize", 5)
    val maxSize = APIUtil.getPropsAsIntValue("stored_procedure_connector.poolMaxSize", 20)
    val timeoutMillis = APIUtil.getPropsAsLongValue("stored_procedure_connector.poolConnectionTimeoutMillis", 3000L)
    val validationQuery = APIUtil.getPropsValue("stored_procedure_connector.poolValidationQuery", "select 1 from dual")
    val poolFactoryName = APIUtil.getPropsValue("stored_procedure_connector.poolFactoryName", "defaultPoolFactory")


    Class.forName(driver)
    val settings = ConnectionPoolSettings(
      initialSize = initialSize,
      maxSize = maxSize,
      connectionTimeoutMillis = timeoutMillis,
      validationQuery = validationQuery,
      connectionPoolFactoryName = poolFactoryName
    )
    ConnectionPool.singleton(url, user, password, settings)
  }


  def callProcedure[T: Manifest](procedureName: String, outBound: TopicTrait): T = {
    val procedureParam: String = write(outBound) // convert OutBound to json string

    var responseJson: String = ""
    DB autoCommit { implicit session =>

      sql"{ CALL ? (?) }"
        .bind(procedureName, procedureParam)
        .executeWithFilters(before,
          statement => {
            val resultSet = statement.getResultSet()
            require(resultSet.next(), s"stored procedure $procedureName must return a json response")
            responseJson = resultSet.getString(1)
          }).apply()
    }
    if(classOf[JValue].isAssignableFrom(manifest[T].runtimeClass)) {
      json.parse(responseJson).asInstanceOf[T]
    } else {
      json.parse(responseJson).extract[T]
    }

  }

}
