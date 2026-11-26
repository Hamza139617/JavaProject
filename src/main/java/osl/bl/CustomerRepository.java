package osl.bl;

import java.sql.SQLException;
import java.util.List;
import osl.bl.AccountService.Session;
import osl.bl.CustomerService.Contact;
import osl.bl.CustomerService.Details;
import osl.bl.CustomerService.Interaction;
import osl.bl.CustomerService.Profile;
import osl.bl.CustomerService.Purchase;

public interface CustomerRepository {
    record History(Profile profile, List<Purchase> purchases, List<Interaction> interactions) {}

    List<Contact> contacts(Session session) throws SQLException;
    History history(Session session, long customerId) throws SQLException;
    long save(Session session, long customerId, Details details) throws SQLException;
    void recordInteraction(Session session, long customerId, Interaction interaction) throws SQLException;
}
