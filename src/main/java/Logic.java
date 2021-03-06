import java.util.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

/**
 * Class for describing logic of watching network activity.
 */
public class Logic extends Thread{
    /**
     * WAN port of host.
     */
    Port WAN;
    /**
     * Bandwidth of link channel.
     */
    Long bandwidth;
    /**
     * LAN port of host.
     */
    Port LAN;
    /**
     * Allowed percent of channel load.
     */
    double percent;
    /**
     * Percent of allowed protocols. When allowed protocols uses much bigger part of bandwidth it's no need to block something.
     */
    double allowedPercent;
    /**
     * List of allowed protocols.
     */
    ArrayList<String> allowed;
    /**
     * List of Policy Maps created for host.
     */
    ArrayList<PolicyMap> policyMaps;
    /**
     * Speed for some protocol after making a shaping.
     */
    long shaper;
    /**
     * Host for which making protocol analyzing.
     */
    Host host;
    /**
     * Update rate in seconds when watch run.
     */
    int update;
    /**
     * TTL for policy map.
     */
    int TTL;

    /**
     * Constructor for logic of watching network activity.
     * @param host host for which making protocol analyzing.
     * @param WAN WAN port of host.
     * @param LAN LAN port of host.
     * @param percent allowed percent of channel load.
     * @param allowedPercent percent of allowed protocols. When allowed protocols uses much bigger part of bandwidth it's no need to block something.
     * @param TTL TTL for policy map.
     * @param update update rate in seconds when watch run.
     */
    public Logic(Host host, Port WAN, Port LAN, double percent, double allowedPercent, int TTL, int update) {
        this.host = host;
        this.WAN = WAN;
        this.LAN = LAN;
        this.percent = percent; //Загруженность канала
        allowed = host.protocols;
        this.allowedPercent = allowedPercent; //Защита от блокирования разрешенных (когда разрешенные "съедают" трафик)
        this.TTL = TTL;
        this.update = update;
        policyMaps = new ArrayList<PolicyMap>();
        SSHClient ssh = new SSHClient(host.getUser(), host.getName(), host.getPassword());
        ArrayList<String> result = ssh.executeCommand("show running-config interface " + WAN.snapshotList.get(WAN.snapshotList.size() - 1).getIfDescr());
        for (String str : result) {
            if (str.contains(" description WAN ")) {
                bandwidth = Long.parseLong(str.replaceAll(" description WAN ", ""))/(8*1000);
                shaper = Long.parseLong(str.replaceAll(" description WAN ", ""))/10;
                break;
            }
        }
        try {
            SNMPClient snmpClient = new SNMPClient(host.getName(), host.getCommunity(), "161");
            snmpClient.start();
            snmpClient.updatePort(WAN, ".1.3.6.1.2.1.2.2");
            snmpClient.stop();
            snmpClient.start();
            snmpClient.updateTrafficTable(".1.3.6.1.4.1.9.9.244.1.2.1", host.ports);
            snmpClient.stop();
            Thread.sleep(5000);
            snmpClient.start();
            snmpClient.updatePort(WAN, ".1.3.6.1.2.1.2.2");
            snmpClient.stop();
            snmpClient.start();
            snmpClient.updateTrafficTable(".1.3.6.1.4.1.9.9.244.1.2.1", host.ports);
            snmpClient.stop();
            Thread.sleep(5000);
            while (true) {
                try {
                    watch();
                    Thread.sleep(update);
                } catch (Exception ee) {
                    System.out.println(ee);
                }
            }
        } catch (Exception ee) {
            System.out.println(ee);
        }
    }

    /**
     * Method to watch network activity on the host.
     */
    private void watch() {
        HashMap<Integer, Traffic> lastDump = WAN.getTimestampList().get(WAN.getTimestampList().size() - 1).getDump();
        HashMap<Integer, Traffic> preLastDump = WAN.getTimestampList().get(WAN.getTimestampList().size() - 2).getDump();
        SNMPClient snmp = new SNMPClient(host.getName(), host.getCommunity(), "161");
        try {
            snmp.start();
            snmp.updatePort(WAN, ".1.3.6.1.2.1.2.2");
            snmp.stop();
        } catch (Exception ee) {
            System.out.println(ee);
        }
        long lastUsageTime = WAN.snapshotList.get(WAN.snapshotList.size() - 1).Time;
        long preLastUsageTime = WAN.snapshotList.get(WAN.snapshotList.size() - 2).Time;
        long lastUsageOctets = WAN.snapshotList.get(WAN.snapshotList.size() - 1).getIfInOctets();
        long preLastUsageOctets = WAN.snapshotList.get(WAN.snapshotList.size() - 2).getIfInOctets();
        double currentBandwidthUsage = (double)(lastUsageOctets - preLastUsageOctets) / (double)(lastUsageTime - preLastUsageTime);
        System.out.println("currentBandwidthUsage "+ currentBandwidthUsage);
        System.out.println("Bandwidth "+ bandwidth);
        System.out.println("Current percent "+ currentBandwidthUsage / bandwidth);
        System.out.println("Allowed percent "+ percent);
        if (currentBandwidthUsage / bandwidth > percent) {
            System.out.println("NBAR analysis");
            HashMap<Integer, Long> delta = new HashMap<Integer, Long>();
            long maxDelta = 0;
            String deltaName = "";
            long deltaSum = 0;
            long maxAllowed = 0;
            long allowedSum = 0;
            for (HashMap.Entry<Integer, Traffic> entry : lastDump.entrySet()) {
                int index = entry.getKey();
                if (preLastDump.get(index) == null) delta.put(index, entry.getValue().getCnpdAllStatsInBytes());
                else
                    delta.put(index, entry.getValue().getCnpdAllStatsInBytes() - preLastDump.get(index).getCnpdAllStatsInBytes());
                System.out.println(lastDump.get(index).getCnpdAllStatsProtocolsName()+ "\n Last usage = "+entry.getValue().getCnpdAllStatsInBytes()+"\n Prelast usage = "+ preLastDump.get(index).getCnpdAllStatsInBytes()+"\n delta = " + delta.get(index));
                if (maxDelta < delta.get(index)) {
                    if (!allowed.contains(lastDump.get(index).getCnpdAllStatsProtocolsName())) {
                        maxDelta = delta.get(index);
                        deltaSum += maxDelta;
                        deltaName = lastDump.get(index).getCnpdAllStatsProtocolsName();
                    } else {
                        maxAllowed = delta.get(index);
                        allowedSum += delta.get(index);
                    }
                }
            }
            //Надо найти сумму разрешенных и неразрешенных октетов и тогда уже блочить
            if (maxDelta != 0) {
                if ((allowedSum / (deltaSum + allowedSum)) < allowedPercent) {
                    System.out.println("Shaping on " + deltaName);
                    makeShaping(deltaName, TTL);
                }
            }
        }
        checkMaps();
        System.out.println("____________________________________________________________");
    }

    /**
     * Method to make the commands for shaping and send them to host.
     * @param deltaName name of protocol.
     * @param ttl time to live of shaping.
     */
    private void makeShaping(String deltaName, int ttl) {
        String Time = getCurrentTimeUsingDate();
        ClassMap CMAP = new ClassMap("match-all", "CMAP" + Time, deltaName);
        PolicyMap PMAP = new PolicyMap("PMAP" + Time, CMAP, shaper);
        SSHClient ssh = new SSHClient(host.getUser(), host.getName(), host.getPassword());
        try {
            ArrayList<String> commands = makeCommand(PMAP);
            System.out.println("Straight commands: ");
            for ( String command: commands
                 ) {
                System.out.println(command);
            }
            ssh.RunCommands(commands);
        } catch (Exception ee) {
            System.out.println(ee);
        }
        PMAP.setReverceCommands(makeReverse(PMAP));
        PMAP.setTTL(ttl);
        policyMaps.add(PMAP);
    }

    /**
     * Method to get string of current time.
     * @return string like "yyyyMMddHHmmss".
     */
    public String getCurrentTimeUsingDate() {
        Date date = new Date();
        String strDateFormat = "yyyyMMddHHmmss";
        DateFormat dateFormat = new SimpleDateFormat(strDateFormat);
        return dateFormat.format(date);
    }

    /**
     * Method to get current date.
     * @return object Date with current parameters.
     */
    public Date getCurrentDate() {
        Date date = new Date();
        return date;
    }

    /**
     * Method to make straight command to set CMAP and PMAP.
     * @param PMAP PMAP which will be convert into commands.
     * @return list of commands
     */
    private ArrayList<String> makeCommand(PolicyMap PMAP) {
        ArrayList<String> commands = new ArrayList<String>();
        commands.add("configure terminal");
        ClassMap CMAP = PMAP.getClassMap();
        commands.add("class-map " + CMAP.getMatch() + " " + CMAP.getName());
        commands.add("match protocol " + CMAP.getProtocol());
        commands.add("exit");
        commands.add("policy-map " + PMAP.getName());
        commands.add("class " + CMAP.getName());
        commands.add("police " + PMAP.getShaper() + " conform-action transmit exceed-action drop");
        commands.add("exit");
        commands.add("exit");
        commands.add("interface " + LAN.snapshotList.get(LAN.snapshotList.size() - 1).getIfDescr());
        commands.add("service-policy output " + PMAP.getName());
        commands.add("exit");
        commands.add("exit");
        return commands;
    }

    /**
     * Method to make reverse command to set CMAP and PMAP.
     * @param PMAP PMAP which will be convert into commands.
     * @return list of commands.
     */
    private ArrayList<String> makeReverse(PolicyMap PMAP) {
        ArrayList<String> commands = new ArrayList<String>();
        commands.add("configure terminal");
        ClassMap CMAP = PMAP.getClassMap();
        commands.add("no class-map " + CMAP.getMatch() + " " + CMAP.getName());
        commands.add("no policy-map " + PMAP.getName());
        commands.add("interface " + LAN.snapshotList.get(LAN.snapshotList.size() - 1).getIfDescr());
        commands.add("no service-policy output " + PMAP.getName());
        commands.add("exit");
        commands.add("exit");
        return commands;
    }

    /**
     * Method to check if PMAP ttl expired.
     */
    public void checkMaps() {
        if (policyMaps.size() != 0) {
            Iterator<PolicyMap> p = policyMaps.iterator();
            while (p.hasNext()) {
                PolicyMap PMAP = p.next();
                String name = PMAP.getName();
                System.out.println("Cheking "+name);
                Date Time = stringToDate(name.replace("PMAP", ""));
                Calendar cal = Calendar.getInstance();
                cal.setTime(Time);
                cal.add(Calendar.SECOND, PMAP.getTTL());
                Date newTime = cal.getTime();
                if (getCurrentDate().after(newTime)) {
                    SSHClient ssh = new SSHClient(host.getUser(), host.getName(), host.getPassword());
                    try {
                        System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!Reverse commands: ");
                        for ( String command: PMAP.getReverceCommands()
                        ) {
                            System.out.println(command);
                        }
                        ssh.RunCommands(PMAP.getReverceCommands());
                    } catch (Exception ee) {
                        System.out.println(ee);
                    }
                    p.remove();
                }
            }
        }
    }

    /**
     * Method to covert string to date format.
     * @param Time time in string format.
     * @return time in DateFormat.
     */
    public Date stringToDate(String Time) {
        Date date = new Date();
        DateFormat format = new SimpleDateFormat("yyyyMMddHHmmss");
        try {
            date = format.parse(Time);
        } catch (Exception ee) {
            System.out.println(ee);
        }
        return date;
    }
}
