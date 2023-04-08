/*
 * Copyright (c) 2012-2014 by the original author
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.powertac.samplebroker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.powertac.common.*;
import org.powertac.common.config.ConfigurableValue;
import org.powertac.common.msg.BalanceReport;
import org.powertac.common.msg.MarketBootstrapData;
import org.powertac.common.repo.TimeslotRepo;
import org.powertac.samplebroker.core.BrokerPropertiesService;
import org.powertac.samplebroker.interfaces.Activatable;
import org.powertac.samplebroker.interfaces.BrokerContext;
import org.powertac.samplebroker.interfaces.Initializable;
import org.powertac.samplebroker.interfaces.MarketManager;
import org.powertac.samplebroker.interfaces.PortfolioManager;
import org.powertac.samplebroker.mcts.Node;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Handles market interactions on behalf of the broker.
 * @author John Collins
 */
@Service
public class MarketManagerService 
implements MarketManager, Initializable, Activatable
{
  static private Logger log = LogManager.getLogger(MarketManagerService.class);
  
  private BrokerContext broker; // broker

  // Spring fills in Autowired dependencies through a naming convention
  @Autowired
  private BrokerPropertiesService propertiesService;

  @Autowired
  private TimeslotRepo timeslotRepo;
  
  @Autowired
  private PortfolioManager portfolioManager;

  // ------------ Configurable parameters --------------
  // max and min offer prices. Max means "sure to trade"
  @ConfigurableValue(valueType = "Double",
          description = "Upper end (least negative) of bid price range")
  private double buyLimitPriceMax = -1.0;  // broker pays

  @ConfigurableValue(valueType = "Double",
          description = "Lower end (most negative) of bid price range")
  private double buyLimitPriceMin = -70.0;  // broker pays

  @ConfigurableValue(valueType = "Double",
          description = "Upper end (most positive) of ask price range")
  private double sellLimitPriceMax = 70.0;    // other broker pays

  @ConfigurableValue(valueType = "Double",
          description = "Lower end (least positive) of ask price range")
  private double sellLimitPriceMin = 0.5;    // other broker pays

  @ConfigurableValue(valueType = "Double",
          description = "Minimum bid/ask quantity in MWh")
  private double minMWh = 0.001; // don't worry about 1 KWh or less

  @ConfigurableValue(valueType = "Integer",
          description = "If set, seed the random generator")
  private Integer seedNumber = null;

  // ---------------- local state ------------------
  private Random randomGen; // to randomize bid/ask prices

  // Bid recording
  private HashMap<Integer, Order> lastOrder;
  private double[] marketMWh;
  private double[] marketPrice;
  private double meanMarketPrice = 0.0;


  private static int maxIterations = 1000;


  static private File ourLog_Weather = new File("ourLog_Weather.txt");
  private FileWriter fileWriter_Weather ;















  public MarketManagerService ()
  {
    super();
  }

  /* (non-Javadoc)
   * @see org.powertac.samplebroker.MarketManager#init(org.powertac.samplebroker.SampleBroker)
   */
  @Override
  public void initialize (BrokerContext broker)
  {
    this.broker = broker;
    lastOrder = new HashMap<>();
    propertiesService.configureMe(this);
    System.out.println("  name=" + broker.getBrokerUsername());
    if (seedNumber != null) {
      System.out.println("  seeding=" + seedNumber);
      log.info("Seeding with : " + seedNumber);
      randomGen = new Random(seedNumber);
    }
    else {
      randomGen = new Random();
    }

    try {
      ourLog_Weather.createNewFile();
      fileWriter_Weather = new FileWriter(ourLog_Weather,false);

    } catch (IOException e) {
      // TODO Auto-generated catch block
      System.out.println("Error creating logFile_Weather.");
      e.printStackTrace();
    }

  }

  // ----------------- data access -------------------
  /**
   * Returns the mean price observed in the market during the bootstrap session.
   */
  @Override
  public double getMeanMarketPrice ()
  {
    return meanMarketPrice;
  }
  
  // --------------- message handling -----------------
  /**
   * Handles the Competition instance that arrives at beginning of game.
   * Here we capture minimum order size to avoid running into the limit
   * and generating unhelpful error messages.
   */
  public synchronized void handleMessage (Competition comp)
  {
    minMWh = Math.max(minMWh, comp.getMinimumOrderQuantity());
  }

  /**
   * Handles a BalancingTransaction message.
   */
  public synchronized void handleMessage (BalancingTransaction tx)
  {

    log.info("Balancing tx: " + tx.getCharge());
  }

  /**
   * Handles a ClearedTrade message - this is where you would want to keep
   * track of market prices.
   */
  public synchronized void handleMessage (ClearedTrade ct)
  {
  }

  /**
   * Handles a DistributionTransaction - charges for transporting power
   */
  public synchronized void handleMessage (DistributionTransaction dt)
  {
    log.info("Distribution tx: " + dt.getCharge());
  }

  /**
   * Handles a CapacityTransaction - a charge for contribution to overall
   * peak demand over the recent past.
   */
  public synchronized void handleMessage (CapacityTransaction dt)
  {
    System.out.println("------------------------------Capacity Fee-----------------------------------");
    System.out.println("Capacity transaction for broker " + dt.getBroker() + " : " + dt.getCharge() + "with threshold for this period : " + dt.getThreshold());
    System.out.println("We were above the threshold by(in KWh) : " + dt.getKWh());
    System.out.println("------------------------------------------------------------------------------------");
    log.info("Capacity tx: " + dt.getCharge());
  }

  /**
   * Receives a MarketBootstrapData message, reporting usage and prices
   * for the bootstrap period. We record the overall weighted mean price,
   * as well as the mean price and usage for a week.
   */
  public synchronized void handleMessage (MarketBootstrapData data)
  {
    marketMWh = new double[broker.getUsageRecordLength()];
    marketPrice = new double[broker.getUsageRecordLength()];
    double totalUsage = 0.0;
    double totalValue = 0.0;
    for (int i = 0; i < data.getMwh().length; i++) {
      totalUsage += data.getMwh()[i];
      totalValue += data.getMarketPrice()[i] * data.getMwh()[i];
      if (i < broker.getUsageRecordLength()) {
        // first pass, just copy the data
        marketMWh[i] = data.getMwh()[i];
        marketPrice[i] = data.getMarketPrice()[i];
      }
      else {
        // subsequent passes, accumulate mean values
        int pass = i / broker.getUsageRecordLength();
        int index = i % broker.getUsageRecordLength();
        marketMWh[index] =
            (marketMWh[index] * pass + data.getMwh()[i]) / (pass + 1);
        marketPrice[index] =
            (marketPrice[index] * pass + data.getMarketPrice()[i]) / (pass + 1);
      }
    }
    meanMarketPrice = totalValue / totalUsage;
  }

  /**
   * Receives a MarketPosition message, representing our commitments on 
   * the wholesale market
   */
  public synchronized void handleMessage (MarketPosition posn)
  {
    broker.getBroker().addMarketPosition(posn, posn.getTimeslotIndex());
  }
  
  /**
   * Receives a new MarketTransaction. We look to see whether an order we
   * have placed has cleared.
   */
  public synchronized void handleMessage (MarketTransaction tx)
  {
    // reset price escalation when a trade fully clears.
    Order lastTry = lastOrder.get(tx.getTimeslotIndex());
    if (lastTry == null) // should not happen
      log.error("order corresponding to market tx " + tx + " is null");
    else if (tx.getMWh() == lastTry.getMWh()) // fully cleared
      lastOrder.put(tx.getTimeslotIndex(), null);
  }
  
  /**
   * Receives market orderbooks. These list un-cleared bids and asks,
   * from which a broker can construct approximate supply and demand curves
   * for the following timeslot.
   */
  public synchronized void handleMessage (Orderbook orderbook)
  {
  }
  
  /**
   * Receives a new WeatherForecast.
   */
  public synchronized void handleMessage (WeatherForecast forecast)
  {
    debugLog(fileWriter_Weather, "----------------------------------");
    debugLog(fileWriter_Weather, "Forecast on timeslot : " + forecast.getTimeslotIndex());

    for (WeatherForecastPrediction p : forecast.getPredictions()) {
      debugLog(fileWriter_Weather, "Time : " + p.getForecastTime());
      debugLog(fileWriter_Weather, "Cloud Cover : " + p.getCloudCover());
      debugLog(fileWriter_Weather, "Temperature : " + p.getTemperature());
      debugLog(fileWriter_Weather, "Wind Speed/Direction : " + p.getWindSpeed() + " / " + p.getWindDirection());

    }
  }

  /**
   * Receives a new WeatherReport.
   */
  public synchronized void handleMessage (WeatherReport report)
  {
    debugLog(fileWriter_Weather, "----------------------------------");
    debugLog(fileWriter_Weather, "Report for timeslot : " + report.getTimeslotIndex());
    debugLog(fileWriter_Weather, "Cloud Cover : " + report.getCloudCover());
    debugLog(fileWriter_Weather, "Temperature : " + report.getTemperature());
    debugLog(fileWriter_Weather, "Wind Speed/Direction : " + report.getWindSpeed() + " / " + report.getWindDirection());
  }

  /**
   * Receives a BalanceReport containing information about imbalance in the
   * current timeslot.
   */
  public synchronized void handleMessage (BalanceReport report)
  {
    System.out.println("Balance report for timeslot " + report.getTimeslotIndex() + " with   : Net imbalance : " + report.getNetImbalance());
  }

  // ----------- per-timeslot activation ---------------

  /**
   * Compute needed quantities for each open timeslot, then submit orders
   * for those quantities.
   *
   * @see org.powertac.samplebroker.interfaces.Activatable#activate(int)
   */
  @Override
  public synchronized void activate (int timeslotIndex)
  {
    double neededKWh = 0.0;
    log.debug("Current timeslot is " + timeslotRepo.currentTimeslot().getSerialNumber());
    for (Timeslot timeslot : timeslotRepo.enabledTimeslots()) { //next 24 timeslots
      int index = (timeslot.getSerialNumber()) % broker.getUsageRecordLength();
      neededKWh = portfolioManager.collectUsage(index);
      submitOrderWithMCTS(neededKWh, timeslot.getSerialNumber());
//      submitOrder(neededKWh, timeslot.getSerialNumber());
    }
  }



  private void submitOrderWithMCTS(double neededKWh, int timeslot) {

    double neededMWh = neededKWh / 1000.0;

    MarketPosition posn =
            broker.getBroker().findMarketPositionByTimeslot(timeslot);
    if (posn != null)
      neededMWh -= posn.getOverallBalance();
    if (Math.abs(neededMWh) <= minMWh) {
      log.info("no power required in timeslot " + timeslot);
      return;
    }
    Double limitPrice = computeLimitPrice(timeslot, neededMWh); //Energy cost in timeslot
    log.info("new order for " + neededMWh + " at " + limitPrice +
            " in timeslot " + timeslot);

    if (neededMWh < 0 && timeslot > 360) {
      Order newOrder = new Order(this.broker.getBroker(), timeslot, neededMWh, limitPrice);
      lastOrder.put(timeslot, newOrder);
      broker.sendMessage(newOrder);
      return;
    }



    double nodeValue = 0.0;
    Node root = new Node(null, 0.0, 0, 0);

    //MCTS
    List<Node> visitedNodes = new ArrayList<Node>();



    double CbalUnitPrice = 2 * buyLimitPriceMin + randomGen.nextGaussian(); //mean = 1, var = 0, gaussian noise
    for (int i=0; i< maxIterations; i++) {

      //Selection-Expansion
      Node current = root;
      int currentTimeslot = timeslot + current.getRelativeTimeslot();
      int hoursUntilAuctionEnd = 24 - current.getRelativeTimeslot();
      double currentNeededMWh = neededMWh;
      double Csim = 0.0;
      double CavgUnit = 0.0;
      double newLimitPrice, clearingPrice;
//      visitedNodes.add(current);

      while (hoursUntilAuctionEnd > 0 && currentNeededMWh > 0) {//while we still haven't reached end of auction simulation

        if (!current.hasChildren()){
          newLimitPrice = computeLimitPrice(currentTimeslot, currentNeededMWh);
          current.setVisitCount(current.getVisitCount() + 1);
          current.expandNode(newLimitPrice);
        }
        if (current.hasUnvisitedChildNodes()) {
          //Get random child and update currentTimeSlot and hoursUntilAuctionEnd
          currentTimeslot++;
          hoursUntilAuctionEnd--;
          current = current.getRandomChild();
          //Find limit price for this timeslot and expand this node
          newLimitPrice = computeLimitPrice(currentTimeslot, currentNeededMWh);
          current.expandNode(newLimitPrice);
          //Keep hours ahead and timeslot in temp vars and run a rollout loop until terminal state
          int tempCurrentTimeslot = currentTimeslot;
          int tempHoursUntilAuctionEnd = hoursUntilAuctionEnd;
          double tempCurrentNeededMWh = currentNeededMWh;

          //until end of simulated auction for currentNode
          while (tempHoursUntilAuctionEnd > 0 && tempCurrentNeededMWh > 0) {

            //Get random action to a child
            int randomAction = randomGen.nextInt(Node.actionNumber+1);
            //if a = 1 -> NOBID
            if (randomAction == 1) {
              tempCurrentTimeslot++;
              tempHoursUntilAuctionEnd--;
              current = new Node(current, Node.NOBID, tempCurrentTimeslot - timeslot, 0);
              continue;
            }

            newLimitPrice = computeLimitPrice(tempCurrentTimeslot, tempCurrentNeededMWh);
            clearingPrice = randomGen.nextGaussian()*Node.sigma + newLimitPrice;

            if (newLimitPrice > clearingPrice) {
              Csim+= tempCurrentNeededMWh* clearingPrice;
              tempCurrentNeededMWh = 0;
              continue;
            }
            tempCurrentTimeslot++;
            tempHoursUntilAuctionEnd--;
          }
          visitedNodes.add(current);

          break;
        }
        //If there are no unvisited chilrdren from this node, get child with best uct value
        current = current.getBestChildUCT(CbalUnitPrice);
        //Simulate from there accordingly
        if (current.getActionId() != Node.NOBID) {
          //See if we cleared market
          newLimitPrice = computeLimitPrice(currentTimeslot, currentNeededMWh);
          clearingPrice = randomGen.nextGaussian()*Node.sigma + newLimitPrice;
          if (newLimitPrice > clearingPrice) {
            Csim+= currentNeededMWh* clearingPrice;
            currentNeededMWh = 0;
            continue;
          }
        }
        visitedNodes.add(current);


      }

      Csim+=CbalUnitPrice*currentNeededMWh;
      CavgUnit = Csim/neededMWh;
      for (Node node : visitedNodes) {
//        System.out.println("Setting visit count for current from : " + node.getVisitCount() + " to " + (node.getVisitCount()+1));
        node.setVisitCount(node.getVisitCount()+1);
//        System.out.println("New visit count : " + node.getVisitCount());
//        System.out.println("Setting CavgUnit for current at :  (" + node.getCavgUnit() + " + " + CavgUnit + ") / 2");

        if (node.getCavgUnit() != 0) {
          node.setCavgUnit((node.getCavgUnit() + CavgUnit) / 2.0);
//          System.out.println("For node on level : " + node.getRelativeTimeslot() + " -> VisitCount : " + node.getVisitCount() + " CavgUnit : " + node.getCavgUnit());
          continue;
        }
//        System.out.println("For node on level : " + node.getRelativeTimeslot() + " -> VisitCount : " + node.getVisitCount() + " CavgUnit : " + node.getCavgUnit());
        node.setCavgUnit(CavgUnit);

      }

    }

    ///// Find best action for this timeslot
    Node bestActionNode = null;
    double bestCavgUnit =  Double.MAX_VALUE;
    for (Node n : root.getChildren()) {
      if (n.getCavgUnit() < bestCavgUnit) {
        bestActionNode = n;
        bestCavgUnit = n.getCavgUnit();
      }
    }

    if (bestActionNode == null) {
      bestActionNode = new Node (root, computeLimitPrice(timeslot, neededMWh), 1,  0);
    }

    //test
    if (bestActionNode.getActionId() > 0) {
      bestActionNode.setActionId(bestActionNode.getActionId() - 0.15*bestActionNode.getActionId());
    }
    else if (bestActionNode.getActionId() > -8) {
      bestActionNode.setActionId(bestActionNode.getActionId() - 0.15 * bestActionNode.getActionId());
    }


    Order order = new Order(this.broker.getBroker(), timeslot, neededMWh, bestActionNode.getActionId());
    lastOrder.put(timeslot, order);
    broker.sendMessage(order);
  }





  /**
   * Composes and submits the appropriate order for the given timeslot.
   */
  private void submitOrder (double neededKWh, int timeslot)
  {
    double neededMWh = neededKWh / 1000.0;

    MarketPosition posn =
        broker.getBroker().findMarketPositionByTimeslot(timeslot);
    if (posn != null)
      neededMWh -= posn.getOverallBalance();
    if (Math.abs(neededMWh) <= minMWh) {
      log.info("no power required in timeslot " + timeslot);
      return;
    }
    Double limitPrice = computeLimitPrice(timeslot, neededMWh); //Energy cost in timeslot
    log.info("new order for " + neededMWh + " at " + limitPrice +
             " in timeslot " + timeslot);
    Order order = new Order(broker.getBroker(), timeslot, neededMWh, limitPrice);
    lastOrder.put(timeslot, order);
//    Node root = new Node(null, 0.0, 0, 0); //0 timeslots ahead
//    System.out.println("Limit price : " + limitPrice);
//    root.expandNode(limitPrice);
//    root.printChildren();
//    System.out.println("Root node created : " + root.toString());


    broker.sendMessage(order);
  }

  /**
   * Computes a limit price with a random element. 
   */
  private Double computeLimitPrice (int timeslot,
                                    double amountNeeded)
  {
    log.debug("Compute limit for " + amountNeeded + 
              ", timeslot " + timeslot);
    // start with default limits
    Double oldLimitPrice;
    double minPrice;
    if (amountNeeded > 0.0) {
      // buying
      oldLimitPrice = buyLimitPriceMax;
      minPrice = buyLimitPriceMin;
    }
    else {
      // selling
      oldLimitPrice = sellLimitPriceMax;
      minPrice = sellLimitPriceMin;
    }
    // check for escalation
    Order lastTry = lastOrder.get(timeslot);
    if (lastTry != null)
      log.debug("lastTry: " + lastTry.getMWh() +
                " at " + lastTry.getLimitPrice());
    if (lastTry != null
        && Math.signum(amountNeeded) == Math.signum(lastTry.getMWh())) {
      oldLimitPrice = lastTry.getLimitPrice();
      log.debug("old limit price: " + oldLimitPrice);
    }

    // set price between oldLimitPrice and maxPrice, according to number of
    // remaining chances we have to get what we need.
    double newLimitPrice = minPrice; // default value
    int current = timeslotRepo.currentSerialNumber();
    int remainingTries = (timeslot - current
                          - Competition.currentCompetition().getDeactivateTimeslotsAhead());
    log.debug("remainingTries: " + remainingTries);
    if (remainingTries > 0) {
      double range = (minPrice - oldLimitPrice) * 2.0 / (double)remainingTries;
      log.debug("oldLimitPrice=" + oldLimitPrice + ", range=" + range);
      double computedPrice = oldLimitPrice + randomGen.nextDouble() * range; 
      return Math.max(newLimitPrice, computedPrice);
    }
    else
      return 0.0; // market order
  }



  void debugLog(FileWriter writer, String log) {
    try {
      writer.write(log);
      writer.write("\n");
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }
}
