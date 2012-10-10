package jbu.cache;


import jbu.offheap.Allocator;

public class OffheapAATree<V> {

    private final Allocator allocator;

    public OffheapAATree(Allocator allocator) {
        this.allocator = allocator;
    }

    public Node skew(Node node) {
        if (node == null) {
            return null;
        } else if (node.getLeftNode() == null) {
            return node;
        } else if (node.getLeftNode().getLevel() == node.getLevel()) {
            //Swap the pointers of horizontal left links.
            Node nodeToSwap = node.getLeftNode();
            node.setLeftNode(nodeToSwap.getRightNode().getAddr());
            nodeToSwap.setRightNode(node.getAddr());
            return nodeToSwap;
        } else {
            return node;
        }
    }


    public Node split(Node node) {
        if (node == null) {
            return null;
        } else if (node.getRightNode() == null || node.getRightNode().getRightNode() == null) {
            return node;
        } else if (node.getLevel() == node.getRightNode().getRightNode().getLevel()) {
            //We have two horizontal right links.  Take the middle node, elevate it, and return it.
            Node nodeToElevate = node.getRightNode();
            node.setRightNode(nodeToElevate.getLeftNode().getAddr());
            nodeToElevate.setLeftNode(node.getAddr());
            nodeToElevate.setLevel(nodeToElevate.getLevel() + 1);
            return nodeToElevate;
        } else {
            return node;
        }
    }


    public Node insert(Node rootNode, Long key, V value) {
        if (rootNode == null) {
            return new Node(allocator, 1, key, value);
        }
        return null;
    }


    public Node delete(Node rootNode, Node newNode) {
        return null;
    }

    public Node decreaseLevel(Node node) {
        return null;
    }
}
