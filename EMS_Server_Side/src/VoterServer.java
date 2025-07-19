import java.io.*;
import static java.lang.System.out;
import java.net.*;
import java.sql.*;
// ①  imports ke list me add karein
import java.sql.Types;
import java.time.LocalDate;   // optional – date check

public class VoterServer {

    private static final int PORT = 54321;

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server started. Listening on port " + PORT);

            while (true) {
                try (Socket socket = serverSocket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

                    String request = in.readLine();
                    if (request == null || request.isEmpty()) {
                        out.println("Invalid Request");
                        continue;
                    }

                    String[] parts = request.split(";", 2);
                    String command = parts[0].toLowerCase();
                    String data = parts.length > 1 ? parts[1] : "";

                    System.out.println("COMMAND RECEIVED: " + command);
                    System.out.println("DATA RECEIVED: " + data);

                    String response = handleCommand(command, data, out);
                    if (!response.isEmpty()) {
                    out.println(response);
}

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

   /** dispatch every request -------------------------- */
private static String handleCommand(String command,
                                    String data,
                                    PrintWriter out) {

    switch (command) {

        /* ===== authentication ===== */
        case "login":            return loginVoter(data);
        case "signup":           return signupVoter(data);

        /* ===== first‑time profile screens ===== */
        case "register_voter":   return registerVoter(data);
        case "register_party":   return registerParty(data);

        /* ===== password recovery ===== */
        case "forgot":           return forgotPassword(data);

        /* ===== admin / maintenance ===== */
        case "add_party":        return addParty(data);
        case "remove_party":     return removeParty(data);

        case "add_candidate":    return addCandidate(data);
        case "update_candidate": return updateCandidate(data);
        case "delete_candidate": return deleteCandidate(data);

        case "update_party":     return updateParty(data);
        case "cast_vote":        return castVote(data);
        
        /* ==== in handleCommand(...) switch ==== */
        case "count_candidates": return countCandidates();      // for Home tab
        case "candidate_count_by_const":                       // OPTIONAL per‑const
            return countCandidatesByConstituency(data);    // not used yet
        case "list_constituencies":
            listConstituencies(out);
            return "";             // reply already sent

        case "list_parties":
            listParties(out);
               return "";

        case "list_candidates":
            listCandidates(data, out);
                return "";
                case "list_const":
        

        /* ===== reports ===== */
        case "fetch_report":
            fetchReport(out);          // writes many lines + END
            return "";                 // we already sent the reply

        default:
            System.out.println("❌ Unknown command: " + command);
            return "Invalid Command";
    }
}


 private static String loginVoter(String data) {
    String[] f = data.split(",");
    if (f.length != 3) return "Invalid Data";

    String cnic     = f[0];
    String password = f[1];
    String role     = f[2];

    /* We only need the columns we test → ph_no and party_id  */
    String sql = "SELECT ph_no, party_id " +
                 "FROM Voter " +
                 "WHERE cnic = ? AND password = ? AND role = ?";

    try (Connection con = getConnection();
         PreparedStatement ps = con.prepareStatement(sql)) {

        ps.setString(1, cnic);
        ps.setString(2, password);
        ps.setString(3, role);

        ResultSet rs = ps.executeQuery();
        if (!rs.next()) {
            return "FAIL";                       // wrong CNIC / password / role
        }

        /* ---------- decide where the client should go next ---------- */
        if ("Voter Candidate".equals(role) && rs.getString("ph_no") == null) {
            return "REGISTER_VOTER";             // first‑time voter
        }
        if ("Party Person".equals(role) && rs.getString("party_id") == null) {
            return "REGISTER_PARTY";             // first‑time party person
        }
        return "SUCCESS";                        // already registered → portal

    } catch (SQLException ex) {
        ex.printStackTrace();
        return "Login Error: " + ex.getMessage();
    }
}

private static String signupVoter(String data) {
    // 8 fields expect karte hain
    String[] f = data.split(";", -1);        //  -1 → blank fields bhi count hon
    if (f.length != 8) return "Invalid Data";

    String name        = f[0].trim();
    String fatherName  = f[1].trim();        // blank allowed
    String cnic        = f[2].trim();
    String dobStr      = f[3].trim();        // blank allowed
    String password    = f[4].trim();
    String question    = f[5].trim();
    String answer      = f[6].trim();
    String role        = f[7].trim();

    // Basic validation ‑‑ jo GUI me bhi hai
    if (name.isEmpty() || cnic.isEmpty() || password.isEmpty()
            || question.isEmpty() || answer.isEmpty() || role.isEmpty()) {
        return "Please fill all mandatory fields.";
    }

    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO Voter (cnic, name, father_name, dob, password, " +
            "security_question, security_answer, role) VALUES (?,?,?,?,?,?,?,?)")) {

        stmt.setString(1, cnic);
        stmt.setString(2, name);

        /* father_name */
        if (fatherName.isEmpty())
            stmt.setNull(3, java.sql.Types.VARCHAR);
        else
            stmt.setString(3, fatherName);

        /* dob */
        if (dobStr.isEmpty())
            stmt.setNull(4, java.sql.Types.DATE);
        else
            stmt.setDate(4, java.sql.Date.valueOf(dobStr));   // yyyy‑MM‑dd

        stmt.setString(5, password);
        stmt.setString(6, question);
        stmt.setString(7, answer);
        stmt.setString(8, role);

        int rows = stmt.executeUpdate();
        return rows > 0 ? "SUCCESS" : "FAIL";

    } catch (SQLIntegrityConstraintViolationException e) {
        return "CNIC already exists.";
    } catch (SQLException e) {
        e.printStackTrace();
        return "Signup Error: " + e.getMessage();
    }
}
/* ------------------------------------------------------------------
   create an **empty** voter row – will be completed in Voter_Registration
   ------------------------------------------------------------------ */
/* --------------------------------------------------------------
   Complete first‑time voter profile.
   data = cnic;name;father;dob;city;phone;gender
-------------------------------------------------------------- */
/* ===============================================================
   Register / Update voter – 6‑field payload
   =============================================================== */
/* --------------------------------------------------------------
   Insert / update a voter’s profile (6 fields – NO father name)
   -------------------------------------------------------------- */
private static String registerVoter(String data) {

    // we now expect ONLY SIX fields:
    // cnic ; name ; dob ; city ; phone ; gender
    String[] f = data.split(";", -1);      // keep empty fields
    if (f.length != 6) return "Invalid Data";

    String cnic   = f[0].trim();
    String name   = f[1].trim();
    String dobStr = f[2].trim();
    String city   = f[3].trim();
    String phone  = f[4].trim();
    String gender = f[5].trim();

    /* If row already exists -> UPDATE.
       Otherwise -> INSERT                                      */
    String sqlInsert = "INSERT INTO Voter "
         + "(cnic, name, dob, city, ph_no, gender, role) "
         + "VALUES (?, ?, ?, ?, ?, ?, 'Voter Candidate')";

    String sqlUpdate = "UPDATE Voter SET "
         + "name = ?, dob = ?, city = ?, ph_no = ?, gender = ?, "
         + "role = 'Voter Candidate' "
         + "WHERE cnic = ?";

    try (Connection con = getConnection()) {

        /* ---------- try UPDATE first ---------- */
        try (PreparedStatement up = con.prepareStatement(sqlUpdate)) {
            up.setString(1, name);
            if (dobStr.isEmpty())
                up.setNull(2, Types.DATE);
            else
                up.setDate(2, java.sql.Date.valueOf(dobStr));

            up.setString(3, city.isEmpty()  ? null : city);
            up.setString(4, phone.isEmpty() ? null : phone);
            up.setString(5, gender.isEmpty() ? null : gender);
            up.setString(6, cnic);

            if (up.executeUpdate() > 0) return "SUCCESS";
        }

        /* ---------- no row updated → do INSERT ---------- */
        try (PreparedStatement ins = con.prepareStatement(sqlInsert)) {
            ins.setString(1, cnic);
            ins.setString(2, name);

            if (dobStr.isEmpty())
                ins.setNull(3, Types.DATE);
            else
                ins.setDate(3, java.sql.Date.valueOf(dobStr));

            ins.setString(4, city.isEmpty()  ? null : city);
            ins.setString(5, phone.isEmpty() ? null : phone);
            ins.setString(6, gender.isEmpty() ? null : gender);

            ins.executeUpdate();
            return "SUCCESS";
        }

    } catch (SQLException ex) {
        ex.printStackTrace();
        return "Register Voter Error: " + ex.getMessage();
    }
}

/* ------------------------------------------------------------------
   first‑time Party‑Person: create skeleton row
   ------------------------------------------------------------------ */
private static String registerParty(String cnic) {
    String sql = "INSERT INTO Voter (cnic, role) VALUES (?, 'Party Person')";
    try (Connection con = getConnection();
         PreparedStatement ps = con.prepareStatement(sql)) {

        ps.setString(1, cnic);
        ps.executeUpdate();
        return "SUCCESS";

    } catch (SQLIntegrityConstraintViolationException dup) {
        return "ALREADY";
    } catch (SQLException ex) {
        ex.printStackTrace();
        return "ERROR:" + ex.getMessage();
    }
}

private static String forgotPassword(String data) {
    String[] parts = data.split(";");
    if (parts.length != 3) return "Invalid Data";

    String cnic = parts[0];
    String question = parts[1];
    String answer = parts[2];

    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(
             "SELECT password FROM Voter WHERE cnic = ? AND security_question = ? AND security_answer = ?")) {

        stmt.setString(1, cnic);
        stmt.setString(2, question);
        stmt.setString(3, answer);

        ResultSet rs = stmt.executeQuery();
        if (rs.next()) {
            String password = rs.getString("password");
            return "SUCCESS:" + password;
        } else {
            return "FAIL";
        }

    } catch (SQLException e) {
        e.printStackTrace();
        return "Forgot Error: " + e.getMessage();
    }
}
private static String addParty(String data) {
    String[] parts = data.split(";");
    if (parts.length != 4) return "Invalid Data";

    String partyName = parts[0];
    String partyId = parts[1];
    String symbol = parts[2];
    String leader = parts[3];

    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(
             "INSERT INTO Party (party_id, party_name, party_symbol, party_leader) VALUES (?, ?, ?, ?)")) {

        stmt.setString(1, partyId);
        stmt.setString(2, partyName);
        stmt.setString(3, symbol);
        stmt.setString(4, leader);

        int rows = stmt.executeUpdate();
        return rows > 0 ? "SUCCESS" : "FAIL";
    } catch (SQLIntegrityConstraintViolationException e) {
        return "Party ID already exists.";
    } catch (SQLException e) {
        e.printStackTrace();
        return "Add Party Error: " + e.getMessage();
    }
}

private static String removeParty(String data) {
    String partyId = data.trim();

    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(
             "DELETE FROM Party WHERE party_id = ?")) {

        stmt.setString(1, partyId);

        int rows = stmt.executeUpdate();
        return rows > 0 ? "SUCCESS" : "NOT_FOUND";
    } catch (SQLException e) {
        e.printStackTrace();
        return "Remove Party Error: " + e.getMessage();
    }
}
private static String addCandidate(String data) {
    String[] parts = data.split(";");
    if (parts.length != 4) return "Invalid Data";

    String name = parts[0];
    String id = parts[1];
    String cnic = parts[2];
    String partyId = parts[3]; // Make sure this is the actual party_id

    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(
             "INSERT INTO Candidate (candidate_id, candidate_name, candidate_cnic, party_id) VALUES (?, ?, ?, ?)")) {

        stmt.setString(1, id);
        stmt.setString(2, name);
        stmt.setString(3, cnic);
        stmt.setString(4, partyId);  // Match column name in DB

        int rows = stmt.executeUpdate();
        return rows > 0 ? "SUCCESS" : "FAIL";

    } catch (SQLIntegrityConstraintViolationException e) {
        return "Candidate ID or CNIC already exists.";
    } catch (SQLException e) {
        e.printStackTrace();
        return "Add Candidate Error: " + e.getMessage();
    }
}
private static String updateCandidate(String data) {
    String[] parts = data.split(";");
    if (parts.length != 4) return "Invalid Data";

    String name = parts[0];
    String id = parts[1];
    String cnic = parts[2];
    String partyId = parts[3]; // Must be party_id (not name)

    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(
             "UPDATE Candidate SET candidate_name = ?, candidate_cnic = ?, party_id = ? WHERE candidate_id = ?")) {

        stmt.setString(1, name);
        stmt.setString(2, cnic);
        stmt.setString(3, partyId);
        stmt.setString(4, id);

        int rows = stmt.executeUpdate();
        return rows > 0 ? "SUCCESS" : "NOT_FOUND";
    } catch (SQLException e) {
        e.printStackTrace();
        return "Update Candidate Error: " + e.getMessage();
    }
}

private static String deleteCandidate(String data) {
    String candidateId = data.trim();

    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(
             "DELETE FROM Candidate WHERE candidate_id = ?")) {

        stmt.setString(1, candidateId);

        int rows = stmt.executeUpdate();
        return rows > 0 ? "SUCCESS" : "NOT_FOUND";
    } catch (SQLException e) {
        e.printStackTrace();
        return "Delete Candidate Error: " + e.getMessage();
    }
}

private static void fetchReport(PrintWriter out) {
    String query = "SELECT p.party_name, p.party_symbol, c.candidate_name, c.candidate_id, COUNT(v.vote_id) AS total_votes " +
                   "FROM Party p " +
                   "JOIN Candidate c ON p.party_id = c.party_id " +
                   "LEFT JOIN Votes v ON c.candidate_id = v.candidate_id " +
                   "GROUP BY c.candidate_id";

    try (Connection conn = getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(query)) {

        while (rs.next()) {
            String row = rs.getString("party_name") + ";" +
                         rs.getString("party_symbol") + ";" +
                         rs.getString("candidate_name") + ";" +
                         rs.getString("candidate_id") + ";" +
                         rs.getInt("total_votes");
            out.println(row);
        }

        out.println("END");
    } catch (SQLException e) {
        e.printStackTrace();
        out.println("ERROR: " + e.getMessage());
        out.println("END");
    }
}

private static String updateParty(String data) {
    String[] parts = data.split(";");
    if (parts.length != 7) return "Invalid Data";

    String cnic = parts[0];
    String name = parts[1];
    String symbol = parts[2];  // You can store this in the Party table if needed
    String leader = parts[3];  // Same for leader
    String password = parts[4];
    String question = parts[5];
    String answer = parts[6];

    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(
             "UPDATE Voter SET name = ?, password = ?, security_question = ?, security_answer = ? " +
             "WHERE cnic = ? AND role = 'Party Person'")) {

        stmt.setString(1, name);
        stmt.setString(2, password);
        stmt.setString(3, question);
        stmt.setString(4, answer);
        stmt.setString(5, cnic);

        int rows = stmt.executeUpdate();
        System.out.println("Voter rows updated: " + rows);  // ✅ For debugging

        return rows > 0 ? "SUCCESS" : "NOT_FOUND";

    } catch (SQLException e) {
        e.printStackTrace();
        return "Update Party Error: " + e.getMessage();
    }
}
private static String castVote(String data) {
    String[] parts = data.split(";");
    if (parts.length != 7) return "Invalid Data";

    String name = parts[0];
    String fatherName = parts[1];
    String cnic = parts[2];
    String constituency = parts[3];
    String partyName = parts[4];     // Not used but received
    String partySymbol = parts[5];   // Not used but received
    String candidateId = parts[6];

    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO Votes (candidate_id, constituency, voter_cnic) VALUES (?, ?, ?)")) {

        stmt.setString(1, candidateId);
        stmt.setString(2, constituency);
        stmt.setString(3, cnic);

        int rows = stmt.executeUpdate();
        return rows > 0 ? "SUCCESS" : "FAIL";
    } catch (SQLException e) {
        e.printStackTrace();
        return "Cast Vote Error: " + e.getMessage();
    }
}

/* -----------------------------------------------------------
   1.  send every constituency (code;name) line by line + END
   ----------------------------------------------------------- */
private static void listConstituencies(PrintWriter out) {
    String sql = "SELECT const_code, const_name FROM Constituency";
    try (Connection c = getConnection();
         Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(sql)) {

        while (rs.next()) {
            out.println(rs.getString(1) + ";" + rs.getString(2));
        }
        out.println("END");

    } catch (SQLException ex) {
        ex.printStackTrace();
        out.println("ERROR:" + ex.getMessage());
        out.println("END");
    }
}

/* -----------------------------------------------------------
   2.  send every party (id;name;symbol) line by line + END
   ----------------------------------------------------------- */
private static void listParties(PrintWriter out) {
    String sql = "SELECT party_id, party_name, party_symbol FROM Party";
    try (Connection c = getConnection();
         Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(sql)) {

        while (rs.next()) {
            out.println(rs.getString(1) + ";" + rs.getString(2) + ";" +
                        rs.getString(3));
        }
        out.println("END");
    } catch (SQLException ex) {
        ex.printStackTrace();
        out.println("ERROR:" + ex.getMessage());
        out.println("END");
    }
}

/* -----------------------------------------------------------
   3.  send candidates for given constituency & party
       syntax: list_candidates;CONST;PARTY_ID
       reply  : name;id line by line + END
   ----------------------------------------------------------- */
private static void listCandidates(String data, PrintWriter out) {
    String[] f = data.split(";", 2);
    if (f.length != 2) {
        out.println("ERROR:Invalid Data");
        out.println("END");
        return;
    }
    String constCode = f[0];
    String partyId   = f[1];

    String sql = "SELECT candidate_name, candidate_id " +
                 "FROM Candidate WHERE party_id = ? AND candidate_id IN " +
                 "(SELECT candidate_id FROM Votes v RIGHT JOIN Candidate c " +
                 "ON v.candidate_id = c.candidate_id WHERE c.party_id=? OR 1=1) " +  // simple filter – adjust if you store constituency elsewhere
                 "ORDER BY candidate_name";

    try (Connection c = getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {

        ps.setString(1, partyId);
        ps.setString(2, partyId);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            out.println(rs.getString(1) + ";" + rs.getString(2));
        }
        out.println("END");

    } catch (SQLException ex) {
        ex.printStackTrace();
        out.println("ERROR:" + ex.getMessage());
        out.println("END");
    }
}

/* --------------------------------------------------------------------
   total candidates in the system
-------------------------------------------------------------------- */
private static String countCandidates() {
    String sql = "SELECT COUNT(*) AS cnt FROM Candidate";
    try (Connection c = getConnection();
         Statement  st = c.createStatement();
         ResultSet  rs = st.executeQuery(sql)) {

        if (rs.next()) return "OK:" + rs.getInt("cnt");
        else            return "ERROR:No data";

    } catch (SQLException ex) {
        ex.printStackTrace();
        return "ERROR:" + ex.getMessage();
    }
}

/* (only if you later want counts per constituency) */
private static String countCandidatesByConstituency(String constName) {
    String sql = "SELECT COUNT(*) AS cnt FROM Candidate WHERE constituency=?";
    try (Connection c = getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {

        ps.setString(1, constName);
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return "OK:" + rs.getInt("cnt");
            else           return "OK:0";
        }

    } catch (SQLException ex) {
        ex.printStackTrace();
        return "ERROR:" + ex.getMessage();
    }
}

    private static Connection getConnection() throws SQLException {
        String url = "jdbc:mysql://127.0.0.1:3306/voter_db";
        String user = "root";      // 🔁 your DB user
        String password = "root";  // 🔁 your DB password
        return DriverManager.getConnection(url, user, password);
    }
}
