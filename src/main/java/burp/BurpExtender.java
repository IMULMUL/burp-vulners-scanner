package burp;

import burp.gui.TabComponent;
import burp.models.Domain;
import burp.models.Software;
import com.codemagi.burp.PassiveScan;
import com.codemagi.burp.ScannerMatch;
import com.monikamorrow.burp.BurpSuiteTab;
//import javax.swing.Timer;

import javax.swing.*;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.Timer;
import java.util.regex.Pattern;


public class BurpExtender extends PassiveScan {

    public static String SETTING_API_KEY_NAME = "SETTING_API_KEY_NAME";
    public static String SETTING_IS_PREMIUM_NAME = "SETTING_IS_PREMIUM_NAME";

    private String apiKey = "";
    private boolean isPremium = false;
    private TabComponent tabComponent;
    private VulnersService vulnersService;
    private final Map<String, Domain> domains = new HashMap<>();
    private final Map<String, Map<String, String>> matchRules = new HashMap<>();

    @Override
    protected void initPassiveScan() {
        extensionName = "Software Vulnerability Scanner";
        settingsNamespace = "VULNERS_";

        BurpSuiteTab mTab = new BurpSuiteTab("Software Vulnerability Scanner", callbacks);
        this.tabComponent = new TabComponent(this, callbacks, domains);

        mTab.addComponent(tabComponent.getRootPanel());

        apiKey = callbacks.loadExtensionSetting(SETTING_API_KEY_NAME);
        tabComponent.setAPIKey(apiKey);

        if(callbacks.loadExtensionSetting(SETTING_IS_PREMIUM_NAME) != null && !callbacks.loadExtensionSetting(SETTING_IS_PREMIUM_NAME).isEmpty())
            isPremium = callbacks.loadExtensionSetting(SETTING_IS_PREMIUM_NAME).equals("true");

        vulnersService = new VulnersService(this, callbacks, helpers, domains, tabComponent);
        try {
            vulnersService.loadRules();
        } catch (IOException e) {
            printError("[VULNERS]" + e.getMessage());
        }

        printOutput("[VULNERS] set Timer to update table");
        Timer timer = new Timer();

        Runnable run = () -> {
            tabComponent.getSoftwareTable().refreshTable(domains, isShowOnlyVuln());
            printOutput("[VULNERS] update table");
        };

        // update table every 5 seconds
        // otherwise updates happen too fast and GUI breaks with loads of NullPointerExceptions
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(run);
            }
        }, 1000, 5000);
    }

    @Override
    public List<IScanIssue> doPassiveScan(IHttpRequestResponse baseRequestResponse) {
        List<IScanIssue> issues = super.doPassiveScan(baseRequestResponse);

        if (getApiKey() == null || getApiKey().isEmpty()) {
            printError("[VULNERS] doPassiveScan There must be an API key.");
            return issues;
        }

        URL url = helpers.analyzeRequest(baseRequestResponse).getUrl();

        /*
         * Here we check possible vulnerabilities related on request path
         */
        if ((tabComponent.getCbxPathScanInScope().isSelected() && !callbacks.isInScope(url)) || !tabComponent.getCbxPathSearch().isSelected()) {
            return issues;
        }

        String domainName = url.getHost();
        String path = url.getPath();
        Domain domain = domains.get(domainName);
        if (domain == null) {
            domains.put(domainName, domain = new Domain());
        }

        if (!domain.getPaths().containsKey(path)) {
            printOutput("[VULNERS] adding new path '" + path + "' for domain " + domainName);
            domain.getPaths().put(path, new HashSet<>());
        }
        vulnersService.checkURLPath(domainName, path, baseRequestResponse);

        return issues;
    }

    @Override
    protected List<IScanIssue> processIssues(List<ScannerMatch> matches, IHttpRequestResponse baseRequestResponse) {
        if (matches.isEmpty()) {
            return super.processIssues(matches, baseRequestResponse);
        }

        if (getApiKey() == null || getApiKey().isEmpty()) {
            printError("[VULNERS] processIssues There must be an API key.");
            return super.processIssues(matches, baseRequestResponse);
        }

        String domainName = helpers.analyzeRequest(baseRequestResponse).getUrl().getHost();
        List<int[]> startStop = new ArrayList<>(1);

        //get the existing matches for this domain
        Domain domain = domains.get(domainName);
        if (domain == null) {
            domains.put(domainName, domain = new Domain());
        }

        String path = helpers.analyzeRequest(baseRequestResponse).getUrl().getPath();
        domains.get(domainName).getPaths().computeIfAbsent(path, k -> new HashSet<>());

        Collections.sort(matches); //matches must be in order
        ScannerMatch lastMatch = null;
        for (ScannerMatch match : matches) {

            // do not continue if software was already found before
            if (domain.getSoftware().get(match.getType() + match.getMatchGroup()) != null) {
                continue;
            }

            // Ignore matches that overlapped previous positions. Usually it's the similar rule match
            if (lastMatch !=null && (lastMatch.getStart() >= match.getStart() || lastMatch.getEnd() >= match.getEnd())) {
                printError("[VULNERS] Ignore overlapped rule " + domainName + " new issue " + match.getFullMatch());
                continue;
            }
            lastMatch = match;

            printOutput("[VULNERS] Processing domain " + domainName + " new issue " + match.getFullMatch());

            Software software = new Software(
                    match.getType() + match.getMatchGroup(),
                    match.getType(),
                    match.getMatchGroup(),

                    matchRules.get(match.getType()).get("type"),
                    matchRules.get(match.getType()).get("alias")
            );

            domains.get(domainName)
                    .getSoftware()
                    .put(software.getKey(), software);

            //add a marker for code highlighting
            startStop.add(new int[]{match.getStart(), match.getEnd()});

            vulnersService.checkSoftware(domainName, software, baseRequestResponse, startStop);
        }

        return new ArrayList<>();
    }

    @Override
    protected IScanIssue getScanIssue(IHttpRequestResponse baseRequestResponse, List<ScannerMatch> matches, List<int[]> startStop) {
        return new SoftwareIssue(baseRequestResponse, helpers, callbacks, this, startStop, new Software("", "", "", "", ""));
    }

    public VulnersService getVulnersService() {
        return vulnersService;
    }

    Map<String, Map<String, String>> getMatchRules() {
        return matchRules;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        apiKey = apiKey.trim();
        Pattern pattern = Pattern.compile("[A-Z0-9]{64,128}");

        if (pattern.matcher(apiKey).matches()) {
            printOutput("[VULNERS] Set API key " + apiKey);
            callbacks.saveExtensionSetting(SETTING_API_KEY_NAME, apiKey);
            this.apiKey = apiKey;

            String isVulnersPremium = vulnersService.isPremiumSubscription();
            printOutput("[VULNERS] Set isPremium " + isVulnersPremium);
            callbacks.saveExtensionSetting(SETTING_IS_PREMIUM_NAME, isVulnersPremium);
            this.isPremium = isVulnersPremium.equals("true");
        } else {
            printError("[VULNERS] Wrong api key provided, should match /[A-Z0-9]{64,128}/ " + apiKey);
            tabComponent.setAPIKey("Wrong API key format, please recheck");
        }
    }

    public void printOutput(String m){
        // Only print debug messages
        if(!tabComponent.getCbxIsDebug().isSelected())
            return;

        callbacks.printOutput(m);
    }

    public void printError(String m){ callbacks.printError(m);}


    public boolean isPremiumSubscription(){
        return isPremium;
    }

    public boolean isShowOnlyVuln() {
        return tabComponent.getCbxSoftwareShowVuln().isSelected();
    }
}