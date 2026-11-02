
public class TestDB {
    public static void main(String[] args) {
        try (java.sql.Connection con =
                 DBConnection.getConnection()) {

            System.out.println(
                "MySQL Connected Successfully!"
            );

        } catch (Exception e) {
            System.out.println(
                "Connection Failed!"
            );
            e.printStackTrace();
        }
    }
}

