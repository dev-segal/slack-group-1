package com;

import com.slack.api.bolt.App;
import com.slack.api.bolt.context.builtin.SlashCommandContext;
import com.slack.api.bolt.jetty.SlackAppServer;
import com.slack.api.model.block.composition.TextObject;

import static com.slack.api.model.block.Blocks.*;
import static com.slack.api.model.block.composition.BlockCompositions.*;
import static com.slack.api.model.block.element.BlockElements.*;

import edu.ksu.canvas.CanvasApiFactory;
import edu.ksu.canvas.TestLauncher;
import edu.ksu.canvas.interfaces.AccountReader;
import edu.ksu.canvas.interfaces.AssignmentReader;
import edu.ksu.canvas.interfaces.CourseReader;
import edu.ksu.canvas.model.Account;
import edu.ksu.canvas.model.Course;
import edu.ksu.canvas.model.assignment.Assignment;
import edu.ksu.canvas.oauth.NonRefreshableOauthToken;
import edu.ksu.canvas.oauth.OauthToken;
import edu.ksu.canvas.requestOptions.ListCourseAssignmentsOptions;
import edu.ksu.canvas.requestOptions.ListCurrentUserCoursesOptions;
import java.lang.ClassNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.net.URI;
import java.sql.*;

public class Main {

    public static Map<String, String> environment = System.getenv();
    static String databaseURL  = environment.get("JDBC_DATABASE_URL");
    static String databaseUsername = environment.get("JDBC_DATABASE_USERNAME");
    static String databasePassword = environment.get("JDBC_DATABASE_PASSWORD");
    protected static String response = "";
    
    public static Connection establishConnection() {
        try {
            Class.forName("org.postgresql.Driver");    
        } catch(ClassNotFoundException e) {
            System.out.println("Something has gone wrong with the database driver");
            System.exit(1);    
        }

        try {
            return DriverManager.getConnection(databaseURL, databaseUsername, databasePassword);
        } catch(SQLException e) {
           e.printStackTrace();
        }

        return null;
    }

    public static void saveUserInformation(String userId, String canvasAccessToken, SlashCommandContext ctx) {
        Connection conn = establishConnection();

        // query inserts new users into DB and updates existing users' tokens
        String query = "INSERT INTO users " +
                        "VALUES(?,?) ON CONFLICT (userid) DO " +
                        "UPDATE SET canvasaccesstoken = ?";

        try {
            PreparedStatement stmt = conn.prepareStatement(query);
            stmt.setString(1, userId);
            stmt.setString(2, canvasAccessToken);
            stmt.setString(3, canvasAccessToken);

            stmt.executeUpdate();
            response = ":heavy_check_mark: We have successfully saved your token!";
        } catch(SQLException e) {
            e.printStackTrace();
            response = "hmm.. we're having trouble saving your token. " +
                    "\n\nWe'll notify our developers immediately";
        }

        try {
            String finalResponse = response;
            ctx.respond(asBlocks(section(s -> s.text(markdownText(finalResponse)))));
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    public static String getCanvasTokenFromUserId(String userId) {
        Connection conn = establishConnection();
        
        String sql = "SELECT * FROM USERS WHERE userid = ?";

        try {
            PreparedStatement pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, userId);
            ResultSet rs = pstmt.executeQuery();

            rs.next();
            return rs.getString(2); // canvas authentication token
        } catch(SQLException e) {
            e.printStackTrace();
        }

        return "";
    }

    public static CanvasGetter setupCanvasGetter(String userId) {
        String canvasAuthToken = getCanvasTokenFromUserId(userId);

        if(!canvasAuthToken.equals("")) {
            return new CanvasGetter(canvasAuthToken);
        } else {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        App app = new App();

        // To prevent heroku from sleeping 
        app.endpoint("GET", "/ping", (req, ctx) -> {
            return ctx.ackWithJson("{status: ok}");
        });

        // display usage information and instructions here
        app.command("/helloworld", (req, ctx) -> {
            return ctx.ack(asBlocks(
                section(s -> s.text(markdownText(":wave: from SlackCan!"))),
                section(s -> s.text(markdownText("Our goal is to place Canvas's most important information at a student's fingertips, right in Slack."))),
                
                divider(),
                
                section(s -> s.text(markdownText(":memo: To setup your account, first follow our basic tutorial on getting an access token from Canvas's dashboard. Once you have it, come back here."))),
                section(s -> s.text(markdownText(":one: Ok, now you can run /canvas-authenticate <token> (put your canvas token after the slash command)"))),
                section(s -> s.text(markdownText(":two: That's all it takes! You're officially connected to Canvas now. Below are a few commands you can get started with."))),

                divider(),

                section(s -> s.text(markdownText("/*helloworld* - To get this message again"))),
                section(s -> s.text(markdownText("/*authenticate-canvas* - To connect to Canvas (you only have to do it once!)"))),
                section(s -> s.text(markdownText("/*upcoming-assignments* - get all upcoming assignments")))
            ));
        });

        app.command("/course-assignment-list", (req, ctx) -> {

            // Returns a numbered list that contains the Courses.
            new Thread(() -> {
                try {
                    CanvasGetter canvasGetter = setupCanvasGetter(req.getPayload().getUserId());
                    String getNumberedListOfCourses = canvasGetter.getNumberedListOfCourses();
                    ctx.respond(asBlocks(
                            divider(),
                            divider(),
                            divider(),
                            section(s -> s.text(markdownText(":thinking_face: Choose a number from the following courses to view the assignments for that course."))),
                            section(s -> s.text(markdownText("Run the following command. /get-me-ass and pass in a number: "))),
                            divider(),
                            section(s -> s.text(markdownText(getNumberedListOfCourses)))
                    ));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            System.out.println("THREAD+++++++ " + Thread.activeCount());
            return ctx.ack("We're getting all your courses that have assignments ...");
        });


        app.command("/get-me-ass", (req, ctx) -> {
            int courseNumber = Integer.parseInt(req.getPayload().getText());

            // Returns a numbered list that contains the Courses.
            new Thread(() -> {
                try {
                    CanvasGetter canvasGetter = setupCanvasGetter(req.getPayload().getUserId());
                    String assignmentsForCourse = canvasGetter.getAssignmentsForCourse(courseNumber);
//                    String courseName =  canvasGetter.getCourse(courseNumber).getName();
                    ctx.respond(asBlocks(
                            section(s -> s.text(markdownText("You chose number: " + courseNumber))),
                            section(s -> s.text(markdownText(":clipboard: *Here are your upcoming assignments:*"))),
                            divider(),
                            section(s -> s.text(markdownText(assignmentsForCourse)))
                    ));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            System.out.println("THREAD+++++++ " + Thread.activeCount());
            return ctx.ack("We're getting your upcoming assignments for chose number " + courseNumber + "...");
        });



        app.command("/authenticate-canvas", (req, ctx) -> {
            String userId = req.getPayload().getUserId();
            String canvasAccessToken = req.getPayload().getText();

            // We need to acknowledge the user's command within 3000 ms, (3 seconds),
            // so we'll do these operations completely independent from the ctx.ack (acknowledgement)
            new Thread(() -> saveUserInformation(userId, canvasAccessToken, ctx)).start();

            return ctx.ack("Token received...");
            });

        app.command("/upcoming-assignments", (req, ctx) -> {
            // launch thread to get upcoming assignments.
            new Thread(() -> {
                try {
                    CanvasGetter canvasGetter = setupCanvasGetter(req.getPayload().getUserId());
                    String upcomingAssignments = canvasGetter.getUpcomingAssignments();
                    ctx.respond(asBlocks(
                            section(s -> s.text(markdownText(":clipboard: *Here are your upcoming assignments:*"))),

                            divider(),

                            section(s -> s.text(markdownText(upcomingAssignments)))
                    ));
                } catch (Exception e) {
                    e.printStackTrace();
                    try {
                        ctx.respond(asBlocks(
                                divider(),
                                section(s -> s.text(markdownText(":no_entry_sign: We couldn't get " +
                                                "in to your Canvas account. :no_entry_sign:" +
                                                "\n\nYour token may have expired.")))
                        ));
                    } catch (Exception ee) {
                        ee.printStackTrace();
                    }
                }
            }).start();

            System.out.println("THREAD+++++++ " + Thread.activeCount());
            return ctx.ack("We're getting the info now...");
        });


        int port = Integer.parseInt(environment.get("PORT"));
        SlackAppServer server = new SlackAppServer(app, port);
        server.start();
    }
}