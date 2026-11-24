package osl.db;

import java.sql.SQLException;
import java.util.Arrays;
import osl.bl.AccountService;

public final class ProvisionAdministrator {
    public static void main(String[] args) {
        var console = System.console();
        if (console == null) {
            System.err.println("Run this command in an interactive terminal.");
            System.exit(1);
            return;
        }
        String username = console.readLine("Username: ");
        String name = console.readLine("Full name: ");
        String phone = console.readLine("Contact number: ");
        char[] password = console.readPassword("Password: ");
        if (username == null || name == null || phone == null || password == null) {
            return;
        }
        try {
            new AccountService(new MySqlAccounts()).provisionAdministrator(name, phone, username,
                    new String(password));
            console.printf("Administrator created.%n");
        } catch (AccountService.AccountException failure) {
            System.err.println(failure.getMessage());
            System.exit(1);
        } catch (SQLException failure) {
            System.err.println("Could not create the administrator. Check the MySQL setup and try again.");
            System.exit(1);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
