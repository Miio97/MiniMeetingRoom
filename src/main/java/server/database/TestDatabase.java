/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package server.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class TestDatabase {

    public static void main(String[] args) {

        System.out.println("Dang thu ket noi MySQL Railway...");

        try (
            Connection conn = DatabaseConnection.getConnection();
            Statement stmt = conn.createStatement()
        ) {

            System.out.println("====================================");
            System.out.println("KET NOI MYSQL THANH CONG!");
            System.out.println("====================================");

            // Kiểm tra database hiện tại
            ResultSet dbResult =
                    stmt.executeQuery("SELECT DATABASE()");

            if (dbResult.next()) {
                System.out.println(
                        "Database dang su dung: "
                        + dbResult.getString(1)
                );
            }

            System.out.println();
            System.out.println("Danh sach cac bang:");

            ResultSet rs =
                    stmt.executeQuery("SHOW TABLES");

            int count = 0;

            while (rs.next()) {

                count++;

                System.out.println(
                        count + ". " + rs.getString(1)
                );
            }

            System.out.println();
            System.out.println(
                    "Tong so bang: " + count
            );

            if (count == 7) {
                System.out.println(
                        "DATABASE MINI MEETING ROOM DA SAN SANG!"
                );
            } else {
                System.out.println(
                        "Can kiem tra lai so luong bang."
                );
            }

        } catch (Exception e) {

            System.out.println("====================================");
            System.out.println("KET NOI MYSQL THAT BAI!");
            System.out.println("====================================");

            System.out.println(
                    "Loi: " + e.getMessage()
            );

            e.printStackTrace();
        }
    }
}
