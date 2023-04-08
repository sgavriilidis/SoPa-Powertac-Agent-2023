package org.powertac.samplebroker.mcts;

import java.util.List;
import java.util.ArrayList;
import java.util.Random;

@SuppressWarnings("unused")
public class Node {

    private double actionId;
    private int visitCount;
    private int relativeTimeslot;

    private Node parent;


    private double CavgUnit;
    private List<Node> children;

    public static  int actionNumber = 2;

    public static double NOBID = -Double.MAX_VALUE + 1;
    //from paper
    private static final int deltaMin = -1;
    private static final int deltaMax = 1;
    public static final int sigma = 6; //according to fig2


    public Node(Node p, double aId, int rT, int vC) {
        this.actionId = aId;
        this.visitCount = vC;
        this.relativeTimeslot = rT;
        this.parent = p;
        this.children = new ArrayList<>();
        this.CavgUnit = 0.0;
    }

    public Node getRandomChild() {
        List<Node> unvisitedChildren = new ArrayList<>();
        for (Node n: this.children) {
            if (n.getVisitCount() == 0) {
                unvisitedChildren.add(n);
            }
        }
        if (unvisitedChildren.isEmpty()) //should theoretically never reach this, kindof works like assert
            return null;
        Random rng = new Random();
        if (unvisitedChildren.size() == 1)
            return unvisitedChildren.get(0);

        int rngIndex = rng.nextInt(unvisitedChildren.size());

        return unvisitedChildren.get(rngIndex);

    }


    public void expandNode(double limitPrice) {

        double minPrice = limitPrice + deltaMin*sigma;
        double maxPrice = limitPrice + deltaMax*sigma;
        double mctsStep = (maxPrice - minPrice)/ (actionNumber);

        //for loop
        double childPrice;
        Node n;

        for (int i=0; i<=actionNumber; i++) {

            childPrice = minPrice+ i*mctsStep;
            n = new Node(this, childPrice, this.relativeTimeslot+1, 0);
            this.children.add(n);

        }
        if (this.relativeTimeslot>0)
        {
            Node node = new Node(this, NOBID, this.relativeTimeslot+1, 0);
            this.children.add(node);
        }

    }


    public Node getBestChildUCT(double CbalUnit) {
        Node bestChild = null;

        Random rng=  new Random();

        double max = - Double.MAX_VALUE;
        for (Node n : this.children) {
            int epsilonInt = rng.nextInt(10);
            double epsilon = (double)epsilonInt / 1000;
            double t = 1 - (n.CavgUnit/CbalUnit);

//            System.out.println("lambdaUCT = " + t + " " + Math.log10(n.parent.visitCount) + " / " + (n.visitCount + 1) + " + " + epsilon);
            double lambdaUCT = t + Math.sqrt( (2 * Math.log10(n.parent.visitCount + 1)) / (n.visitCount + 1)) + epsilon;
//            System.out.println("Child : " + n.actionId + " with lambda : " + lambdaUCT );
            if (lambdaUCT > max) {
                max = lambdaUCT;
                bestChild = n;
            }
        }

        if (bestChild == null) {
            System.out.println("Not found best child UCT");
        }

        return bestChild;
    }


    public boolean hasUnvisitedChildNodes() {
        for (Node c : this.children) {
            if (c.isVisited())
                return false;
        }

        return true;
    }



    private boolean isVisited() {
        return this.visitCount > 0;
    }

    private boolean isRoot() {
        return this.parent == null;

    }




    public void printChildren() {
        int count = 1;
        for (Node c : this.children) {
            System.out.println("-------------------------------------");
            System.out.println("Child : " + count);
            System.out.println(c.toString());
            count++;
        }
    }

    public boolean hasChildren() {

        return !this.children.isEmpty();
    }









    public double getActionId() {
        return actionId;
    }

    public void setActionId(double actionId) {
        this.actionId = actionId;
    }

    public int getVisitCount() {
        return visitCount;
    }

    public void setVisitCount(int visitCount) {
        this.visitCount = visitCount;
    }

    public int getRelativeTimeslot() {
        return relativeTimeslot;
    }

    public void setRelativeTimeslot(int relativeTimeslot) {
        this.relativeTimeslot = relativeTimeslot;
    }

    public Node getParent() {
        return parent;
    }

    public void setParent(Node parent) {
        this.parent = parent;
    }

    public double getCavgUnit() {
        return CavgUnit;
    }

    public void setCavgUnit(double cavgUnit) {
        this.CavgUnit = cavgUnit;
    }

    public List<Node> getChildren() {
        return children;
    }

    public void setChildren(List<Node> children) {
        this.children = children;
    }

    public static int getActionNumber() {
        return actionNumber;
    }

    public static void setActionNumber(int actionNumber) {
        Node.actionNumber = actionNumber;
    }




}
