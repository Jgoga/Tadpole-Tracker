package sproj;

//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;

import sproj.util.Logger;

public class Main {

    static final Logger logger = new sproj.util.Logger();   // LogManager.getLogger("Main");

    private Main(){/* not to be instantiated*/}

    public static void main(String[] args) throws Exception {

        try {
            TrackerApp.launch();
        } catch (Exception e) {
            // todo    specific error handling, etc
//            e.printStackTrace();
            logger.fatal(e);
            throw e;
        } finally {
            // todo
            logger.info("Exiting SinglePlateTracker App");
        }
        System.exit(0);     // todo find out why the app isn't exiting even after an Exception is thrown
    }
}
