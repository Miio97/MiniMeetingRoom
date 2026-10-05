/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package server.database;

/**
 *
 * @author HieuHoc
 */

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {
    private static final String HOST = "mainline.proxy.rlwy.net";
    private static final String PORT = "13954";
    private static final String DATABASE = "railway";
    private static final String USER = "root";
    private static final String PASSWORD = "KMEPJjAjbsaZPSYJCnkYfxjTELDVumLj";

    private static final String URL =
            "jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE
            + "?useUnicode=true"
            + "&characterEncoding=UTF-8"
            + "&serverTimezone=UTC"
            + "&allowPublicKeyRetrieval=true";

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                URL,
                USER,
                PASSWORD
        );
    }
}