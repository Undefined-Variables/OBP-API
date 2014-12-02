package code.sandbox

import code.metadata.counterparties.{MongoCounterparties, Metadata}
import code.model._
import code.model.dataAccess._
import net.liftweb.common._
import java.util.UUID
import net.liftweb.mongodb.record.MongoRecord
import net.liftweb.util.Helpers._
import org.bson.types.ObjectId

//An basic implementation of Saveable for MongoRecords
case class SaveableMongoObj[T <: MongoRecord[_]](value : T) extends Saveable[T] {
  def save() = value.save(true)
}

/**
 * Imports data into the format used by LocalConnector (e.g. HostedBank)
 */
object LocalConnectorDataImport extends OBPDataImport {

  type BankType = HostedBank
  type AccountType = Account
  type TransactionType = OBPEnvelope
  type MetadataType = Metadata

  //TODO: this only works after createdUsers have been saved (and thus an APIUser has been created
  protected def setAccountOwner(owner : AccountOwnerEmail, account: Account, createdUsers: List[OBPUser]) : Unit = {
    val apiUserOwner = createdUsers.find(obpUser => owner == obpUser.email.get).flatMap(_.user.obj)

    apiUserOwner match {
      case Some(o) => {
        MappedAccountHolder.create
          .user(o)
          .accountBankPermalink(account.bankId.value)
          .accountPermalink(account.accountId.value).save
      }
      case None => {
        //This shouldn't happen as OBPUser should generate the APIUsers when saved
        logger.error(s"api user(s) with email $owner not found.")
        logger.error("Data import completed with errors.")
      }
    }
  }

  protected def createSaveableBanks(data : List[SandboxBankImport]) : Box[List[Saveable[BankType]]] = {
    val hostedBanks = data.map(b => {
      HostedBank.createRecord
        .id(ObjectId.get)
        .permalink(b.id)
        .name(b.full_name)
        .alias(b.short_name)
        .website(b.website)
        .logoURL(b.logo)
        .national_identifier(b.id) //this needs to match up with what goes in the OBPEnvelopes
    })

    val validationErrors = hostedBanks.flatMap(_.validate)

    if(!validationErrors.isEmpty) {
      Failure(s"Errors: ${validationErrors.map(_.msg)}")
    } else {
      Full(hostedBanks.map(SaveableMongoObj(_)))
    }
  }

  protected def createSaveableAccount(acc : SandboxAccountImport, banks : List[HostedBank]) : Box[Saveable[Account]] = {
    def getHostedBank(acc : SandboxAccountImport) = Box(banks.find(b => b.permalink.get == acc.bank))

    def asSaveableAccount(account : Account, hostedBank : HostedBank) = new Saveable[Account] {
      val value = account

      def save() = {
        //this looks pointless, but what it is doing is refreshing the Account.bankID.obj reference, which
        //is used by Account.bankId. If we don't refresh it here, Account.bankId will return BankId("")
        account.bankID(account.bankID.get).save(true)
      }
    }

    for {
      hBank <- getHostedBank(acc) ?~ {
        logger.warn("hosted bank not found")
        "Server error"
      }
      balance <- tryo{BigDecimal(acc.balance.amount)} ?~ s"Invalid balance: ${acc.balance.amount}"
    } yield {
      val account = Account.createRecord
        .permalink(acc.id)
        .bankID(hBank.id.get)
        .accountLabel(acc.label)
        .accountCurrency(acc.balance.currency)
        .accountBalance(balance)
        .accountNumber(acc.number)
        .kind(acc.`type`)
        .accountIban(acc.IBAN)

      asSaveableAccount(account, hBank)
    }
  }

  protected def createSaveableTransactionsAndMetas(transactions : List[SandboxTransactionImport], createdBanks : List[BankType], createdAccounts : List[AccountType]):
    Box[(List[Saveable[TransactionType]], List[Saveable[MetadataType]])] = {

    // a bit ugly to have this as a var
    var metadatasToSave : List[Metadata] = Nil

    val envs : List[Box[OBPEnvelope]] = transactions.map(t => {

      type Counterparty = String

      def createMeta(holder : String, publicAlias : String, accountNumber : String) = {
        Metadata.createRecord
          .holder(holder)
          .accountNumber(accountNumber)
          .originalPartyAccountId(t.this_account.id)
          .originalPartyBankId(t.this_account.bank)
          .publicAlias(publicAlias)
      }

      def findExistingMetadata(counter : SandboxTransactionCounterparty) = {
        //find by name and account number
        counter.name match {
          case Some(name) =>
            metadatasToSave.find(m => {
              m.holder.get == name &&
                m.accountNumber.get == counter.account_number.getOrElse("")
            })
          case None => {
            counter.account_number match {
              case Some(accNum) =>
                metadatasToSave.find(m => {
                  m.accountNumber.get == accNum
                })
              case None => None
            }
          }
        }
      }

      def generateNewMetadata(accountNumber : Option[String]) : Metadata = {
        val holder = "unknown_" + UUID.randomUUID.toString
        val publicAlias = MongoCounterparties.newPublicAliasName(BankId(t.this_account.bank), AccountId(t.this_account.id))
        createMeta(holder, publicAlias, accountNumber.getOrElse(""))
      }

      def newMetadataFromCounterparty(counter : SandboxTransactionCounterparty) : Metadata = {
        val counterAccNumber = counter.account_number.getOrElse("")
        counter.name match {
          case Some(n) if n.nonEmpty => {
            val publicAlias = MongoCounterparties.newPublicAliasName(BankId(t.this_account.bank), AccountId(t.this_account.id))
            createMeta(n, publicAlias, counterAccNumber)
          }
          case _ => generateNewMetadata(Some(counterAccNumber))
        }
      }

      val metadata = t.counterparty match {
        case Some(counter) => {
          val existingMeta = findExistingMetadata(counter)
          existingMeta.getOrElse(newMetadataFromCounterparty(counter))
        }
        case None => generateNewMetadata(None)
      }

      for {
        createdBank <- Box(createdBanks.find(b => b.permalink.get == t.this_account.bank)) ?~
          s"Transaction this_account bank must be specified in import banks. Unspecified bank: ${t.this_account.bank}"
        //have to compare a.bankID to createdBank.id instead of just checking a.bankId against t.this_account.bank as createdBank hasn't been
        //saved so the a.bankId method (which involves a db lookup) will not work
        createdAcc <- Box(createdAccounts.find(a => a.bankID.toString == createdBank.id.get.toString && a.accountId == AccountId(t.this_account.id))) ?~
          s"Transaction this_account account must be specified in import accounts. Unspecified account id: ${t.this_account.id} at bank: ${t.this_account.bank}"
        newBalanceValue <- tryo{BigDecimal(t.details.new_balance)} ?~ s"Invalid new balance: ${t.details.new_balance}"
        tValue <- tryo{BigDecimal(t.details.value)} ?~ s"Invalid transaction value: ${t.details.value}"
        postedDate <- tryo{dateFormat.parse(t.details.posted)} ?~ s"Invalid date format: ${t.details.posted}. Expected pattern $datePattern"
        completedDate <-tryo{dateFormat.parse(t.details.completed)} ?~ s"Invalid date format: ${t.details.completed}. Expected pattern $datePattern"
      } yield {

        //bankNationalIdentifier not available from  createdAcc.bankNationalIdentifier as it hasn't been saved so we get it from createdBank
        val obpThisAccountBank = OBPBank.createRecord
          .national_identifier(createdBank.national_identifier.get)

        val obpThisAccount = OBPAccount.createRecord
          .holder(createdAcc.holder.get)
          .number(createdAcc.accountNumber.get)
          .kind(createdAcc.kind.get)
          .bank(obpThisAccountBank)

        val counterpartyAccountNumber = t.counterparty.flatMap(_.account_number)

        val obpOtherAccount = OBPAccount.createRecord
          .holder(metadata.holder.get)
          .number(counterpartyAccountNumber.getOrElse(""))

        val newBalance = OBPBalance.createRecord
          .amount(newBalanceValue)
          .currency(createdAcc.accountCurrency.get)

        val transactionValue = OBPValue.createRecord
          .amount(tValue)
          .currency(createdAcc.accountCurrency.get)

        val obpDetails = OBPDetails.createRecord
          .completed(completedDate)
          .posted(postedDate)
          .kind(t.details.`type`)
          .label(t.details.description)
          .new_balance(newBalance)
          .value(transactionValue)


        val obpTransaction = OBPTransaction.createRecord
          .details(obpDetails)
          .this_account(obpThisAccount)
          .other_account(obpOtherAccount)

        val env = OBPEnvelope.createRecord
          .transactionId(t.id)
          .obp_transaction(obpTransaction)

        //add the metadatas to the list of them to save
        metadatasToSave = metadata :: metadatasToSave
        env
      }

    })

    val envelopes = dataOrFirstFailure(envs)
    envelopes.map(es => (es.map(SaveableMongoObj(_)), metadatasToSave.map(SaveableMongoObj(_))))
  }

}
