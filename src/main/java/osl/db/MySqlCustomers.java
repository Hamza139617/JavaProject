package osl.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import osl.bl.AccountService.Role;
import osl.bl.AccountService.Session;
import osl.bl.CustomerRepository;
import osl.bl.CustomerService.Contact;
import osl.bl.CustomerService.CustomerException;
import osl.bl.CustomerService.Details;
import osl.bl.CustomerService.DuplicateCustomer;
import osl.bl.CustomerService.Interaction;
import osl.bl.CustomerService.Kind;
import osl.bl.CustomerService.Profile;
import osl.bl.CustomerService.Purchase;
import osl.bl.CustomerService.PurchasedItem;
import osl.bl.StockService.Unit;
import osl.db.MySqlDatabase.WorkspaceLock;

public final class MySqlCustomers implements CustomerRepository {
    private record ReceiptRows(long id, LocalDateTime soldAt, BigDecimal total, List<PurchasedItem> items) {}

    @Override
    public List<Contact> contacts(Session session) throws SQLException {
        try (var connection = MySqlDatabase.open()) {
            long business = retailWorkspace(connection, session, WorkspaceLock.NONE);
            try (var query = connection.prepareStatement("""
                    SELECT customer_id,full_name,phone FROM customers
                    WHERE business_id=? ORDER BY full_name,customer_id
                    """)) {
                query.setLong(1, business);
                try (var rows = query.executeQuery()) {
                    List<Contact> values = new ArrayList<>();
                    while (rows.next()) values.add(contact(rows));
                    return values;
                }
            }
        }
    }

    @Override
    public History history(Session session, long customerId) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            long business = retailWorkspace(connection, session, WorkspaceLock.NONE);
            Profile profile = profile(connection, business, customerId);
            return new History(profile, purchases(connection, business, customerId), interactions(connection, customerId));
        });
    }

    @Override
    public long save(Session session, long customerId, Details details) throws SQLException {
        return MySqlDatabase.transaction(connection -> {
            long business = retailWorkspace(connection, session, WorkspaceLock.EXCLUSIVE);
            if (customerId != 0 && !belongsTo(connection, business, customerId)) throw unavailable();
            try (var query = connection.prepareStatement("""
                    SELECT customer_id,full_name,phone FROM customers WHERE business_id=? AND phone=? AND customer_id<>?
                    """)) {
                query.setLong(1, business);
                query.setString(2, details.phone());
                query.setLong(3, customerId);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) throw new DuplicateCustomer(contact(rows));
                }
            }
            String sql = customerId == 0
                    ? "INSERT INTO customers (full_name,phone,preferences,notes,business_id) VALUES (?,?,?,?,?)"
                    : "UPDATE customers SET full_name=?,phone=?,preferences=?,notes=? WHERE business_id=? AND customer_id=?";
            try (var save = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                save.setString(1, details.name());
                save.setString(2, details.phone());
                save.setString(3, details.preferences());
                save.setString(4, details.notes());
                save.setLong(5, business);
                if (customerId != 0) save.setLong(6, customerId);
                save.executeUpdate();
                return customerId == 0 ? MySqlDatabase.generatedId(save.getGeneratedKeys()) : customerId;
            }
        });
    }

    @Override
    public void recordInteraction(Session session, long customerId, Interaction interaction) throws SQLException {
        MySqlDatabase.transaction(connection -> {
            long business = retailWorkspace(connection, session, WorkspaceLock.EXCLUSIVE);
            if (!belongsTo(connection, business, customerId)) throw unavailable();
            try (var insert = connection.prepareStatement("""
                    INSERT INTO customer_interactions (customer_id,interaction_type,interaction_on,rating,notes)
                    VALUES (?,?,?,?,?)
                    """)) {
                insert.setLong(1, customerId);
                insert.setString(2, interaction.kind().name());
                insert.setObject(3, interaction.date());
                insert.setObject(4, interaction.rating());
                insert.setString(5, interaction.notes());
                insert.executeUpdate();
            }
            return null;
        });
    }

    static boolean belongsTo(Connection connection, long business, long id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM customers WHERE business_id=? AND customer_id=?")) {
            query.setLong(1, business);
            query.setLong(2, id);
            try (var rows = query.executeQuery()) { return rows.next(); }
        }
    }

    private static Profile profile(Connection connection, long business, long id) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT customer_id,full_name,phone,preferences,notes FROM customers WHERE business_id=? AND customer_id=?
                """)) {
            query.setLong(1, business);
            query.setLong(2, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw unavailable();
                return new Profile(contact(rows), rows.getString("preferences"), rows.getString("notes"));
            }
        }
    }

    private static List<Purchase> purchases(Connection connection, long business, long customerId) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT s.sale_id,s.sold_at,s.revenue,l.product_id,MAX(i.product_name) AS product_name,
                       i.unit,SUM(i.quantity) AS quantity
                FROM sales s
                JOIN sale_items i ON i.sale_id=s.sale_id
                JOIN stock_lots l ON l.lot_id=i.lot_id
                WHERE s.business_id=? AND s.customer_id=? AND s.sale_type='RETAIL'
                GROUP BY s.sale_id,s.sold_at,s.revenue,l.product_id,i.unit
                ORDER BY s.sold_at DESC,s.sale_id DESC,product_name,l.product_id
                """)) {
            query.setLong(1, business);
            query.setLong(2, customerId);
            try (var rows = query.executeQuery()) {
                Map<Long, ReceiptRows> receipts = new LinkedHashMap<>();
                while (rows.next()) {
                    long id = rows.getLong("sale_id");
                    ReceiptRows sale = receipts.get(id);
                    if (sale == null) {
                        sale = new ReceiptRows(id, rows.getObject("sold_at", LocalDateTime.class),
                                rows.getBigDecimal("revenue"), new ArrayList<>());
                        receipts.put(id, sale);
                    }
                    sale.items().add(new PurchasedItem(rows.getLong("product_id"), rows.getString("product_name"),
                            Unit.valueOf(rows.getString("unit")), rows.getBigDecimal("quantity")));
                }
                return receipts.values().stream().map(sale ->
                        new Purchase(sale.id(), sale.soldAt(), sale.total(), sale.items())).toList();
            }
        }
    }

    private static List<Interaction> interactions(Connection connection, long customer) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT interaction_type,interaction_on,rating,notes FROM customer_interactions
                WHERE customer_id=? ORDER BY interaction_on DESC, interaction_id DESC
                """)) {
            query.setLong(1, customer);
            try (var rows = query.executeQuery()) {
                List<Interaction> interactions = new ArrayList<>();
                while (rows.next()) {
                    interactions.add(new Interaction(Kind.valueOf(rows.getString("interaction_type")),
                            rows.getObject("interaction_on", LocalDate.class),
                            rows.getObject("rating", Integer.class), rows.getString("notes")));
                }
                return interactions;
            }
        }
    }

    private static Contact contact(ResultSet row) throws SQLException {
        return new Contact(row.getLong("customer_id"), row.getString("full_name"), row.getString("phone"));
    }

    private static long retailWorkspace(Connection connection, Session session, WorkspaceLock lock) throws SQLException {
        if (session.role() != Role.RETAILER) throw new CustomerException("Customer relationships are available to retailers.");
        return MySqlDatabase.workspace(connection, session, lock);
    }

    private static CustomerException unavailable() {
        return new CustomerException("This customer is unavailable in your workspace.");
    }
}
