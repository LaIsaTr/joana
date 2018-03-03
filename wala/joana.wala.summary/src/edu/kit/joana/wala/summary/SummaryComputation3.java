/**
 * This file is part of the Joana IFC project. It is developed at the
 * Programming Paradigms Group of the Karlsruhe Institute of Technology.
 *
 * For further details on licensing please read the information at
 * http://joana.ipd.kit.edu or contact the authors.
 */
package edu.kit.joana.wala.summary;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import org.jgrapht.DirectedGraph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;

import edu.kit.joana.ifc.sdg.graph.BitVector;
import edu.kit.joana.ifc.sdg.graph.LabeledSDGEdge;
import edu.kit.joana.ifc.sdg.graph.SDG;
import edu.kit.joana.ifc.sdg.graph.SDGEdge;
import edu.kit.joana.ifc.sdg.graph.SDGNode;
import edu.kit.joana.util.collections.IntIntSimpleVector;
import edu.kit.joana.util.collections.Intrusable;
import edu.kit.joana.util.collections.IntrusiveList;
import edu.kit.joana.util.collections.SimpleVectorBase;
import edu.kit.joana.util.graph.EfficientGraph;
import edu.kit.joana.util.graph.TarjanStrongConnectivityInspector;
import edu.kit.joana.wala.summary.MainChangeTest.RememberReachedBitVector;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.set.TIntSet;

/**
 * @author Juergen Graf <graf@kit.edu>
 *
 */
public class SummaryComputation3< G extends DirectedGraph<SDGNode, SDGEdge> & EfficientGraph<SDGNode, SDGEdge>> {

	private final HashSet<Edge> pathEdge;
    private final Set<Integer> procedureWorkSet;
    private final Map<Integer, IntrusiveList<Edge>> worklists;
    private final G graph;
    private final TIntSet relevantFormalIns;
    private final TIntSet relevantProcs;
    private final TIntSet fullyConnected;
    private final TIntObjectMap<List<SDGNode>> out2in;
    private final boolean rememberReached;
    private final SDGEdge.Kind sumEdgeKind;
    private final Set<SDGEdge.Kind> relevantEdges;
    private final String annotate;
    private final IntIntSimpleVector nodeId2ProcLocalNodeId;
    private final SimpleVectorBase<Integer, SDGNode[]> procLocalNodeId2Node;
    private final List<Set<Integer>> procSccs;
    private final IntIntSimpleVector indexNumberOf;
    
    private IntrusiveList<Edge> current; 

	private SummaryComputation3(G graph, TIntSet relevantFormalIns,
			TIntSet relevantProcs, TIntSet fullyConnected, TIntObjectMap<List<SDGNode>> out2in,
			boolean rememberReached, SDGEdge.Kind sumEdgeKind, Set<SDGEdge.Kind> relevantEdges,
			String annotate) {
    	this.graph = graph;
    	this.relevantFormalIns = relevantFormalIns;
    	this.relevantProcs = relevantProcs;
    	this.fullyConnected = fullyConnected;
        this.pathEdge = new HashSet<Edge>();
        int maxProcNumber = -1;
        {
            final DirectedGraph<Integer, DefaultEdge> callGraph = extractCallGraph(graph);
            final TarjanStrongConnectivityInspector<Integer, DefaultEdge> sccInspector = new TarjanStrongConnectivityInspector<>(callGraph);
            
            this.procSccs = sccInspector.stronglyConnectedSets();
            assert procSccs instanceof RandomAccess;
            
            final Map<Integer, TarjanStrongConnectivityInspector.VertexNumber<Integer>> indices = sccInspector.getVertexToVertexNumber();
            this.indexNumberOf = new IntIntSimpleVector(0, 1);
            for (Entry<Integer, TarjanStrongConnectivityInspector.VertexNumber<Integer>> entry : indices.entrySet()) {
            	maxProcNumber = Math.max(maxProcNumber, entry.getKey());
                indexNumberOf.put(entry.getKey(), entry.getValue().getSccNumber());
            }
            indexNumberOf.trimToSize();
            
            this.procedureWorkSet = new TreeSet<>(new Comparator<Integer>() {
            	@Override
            	public int compare(Integer o1, Integer o2) {
            		final int sccCompare = Integer.compare(indexNumberOf.getInt(o1), indexNumberOf.getInt(o2));
    				if (sccCompare != 0) return sccCompare;
    				
    				return Integer.compare(o1, o2);
            	}
			});
        }
        
        this.worklists = new SimpleVectorBase<Integer, IntrusiveList<Edge>>(0, 1) {
            protected int getId(Integer k) {
            	return k;
            };
        };
        
        this.out2in = out2in;
        this.rememberReached = rememberReached;
        this.sumEdgeKind = sumEdgeKind;
        this.relevantEdges = relevantEdges;
        this.annotate = annotate;
        this.nodeId2ProcLocalNodeId = new IntIntSimpleVector(0, graph.vertexSet().size());
        this.procLocalNodeId2Node = new SimpleVectorBase<Integer, SDGNode[]>(0, maxProcNumber) {
        	@Override
        	protected int getId(Integer procNumber) {
        		return procNumber;
        	}
		};
	}

	public static int compute(WorkPackage<SDG> pack, IProgressMonitor progress) throws CancelException {
		// default summary computation follows control and date dependencies
		Set<SDGEdge.Kind> relevantEdges = new HashSet<SDGEdge.Kind>();
		relevantEdges.add(SDGEdge.Kind.DATA_DEP);
		relevantEdges.add(SDGEdge.Kind.DATA_HEAP);
		relevantEdges.add(SDGEdge.Kind.DATA_ALIAS);
		relevantEdges.add(SDGEdge.Kind.DATA_LOOP);
		relevantEdges.add(SDGEdge.Kind.DATA_DEP_EXPR_VALUE);
		relevantEdges.add(SDGEdge.Kind.DATA_DEP_EXPR_REFERENCE);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_COND);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_UNCOND);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_EXPR);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_CALL);
		relevantEdges.add(SDGEdge.Kind.JUMP_DEP);
		relevantEdges.add(SDGEdge.Kind.SUMMARY);
		relevantEdges.add(SDGEdge.Kind.SUMMARY_DATA);
		relevantEdges.add(SDGEdge.Kind.SUMMARY_NO_ALIAS);
		relevantEdges.add(SDGEdge.Kind.SYNCHRONIZATION);

		return compute(pack, SDGEdge.Kind.SUMMARY, relevantEdges, progress);
	}

	public static int computeAdjustedAliasDep(WorkPackage<SDG> pack, IProgressMonitor progress) throws CancelException {
		// default summary computation follows control and date dependencies
		Set<SDGEdge.Kind> relevantEdges = new HashSet<SDGEdge.Kind>();
		relevantEdges.add(SDGEdge.Kind.DATA_DEP);
		relevantEdges.add(SDGEdge.Kind.DATA_HEAP);
		relevantEdges.add(SDGEdge.Kind.DATA_ALIAS);
		relevantEdges.add(SDGEdge.Kind.DATA_LOOP);
		relevantEdges.add(SDGEdge.Kind.DATA_DEP_EXPR_VALUE);
		relevantEdges.add(SDGEdge.Kind.DATA_DEP_EXPR_REFERENCE);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_COND);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_UNCOND);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_EXPR);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_CALL);
		relevantEdges.add(SDGEdge.Kind.JUMP_DEP);
//		relevantEdges.add(SDGEdge.Kind.SUMMARY);
//		relevantEdges.add(SDGEdge.Kind.SUMMARY_DATA);
		relevantEdges.add(SDGEdge.Kind.SUMMARY_NO_ALIAS);
		relevantEdges.add(SDGEdge.Kind.SYNCHRONIZATION);

		return compute(pack, SDGEdge.Kind.SUMMARY_DATA, relevantEdges, progress);
	}

	public static int computePureDataDep(WorkPackage<SDG> pack, IProgressMonitor progress) throws CancelException {
		// default summary computation follows control and date dependencies
		Set<SDGEdge.Kind> relevantEdges = new HashSet<SDGEdge.Kind>();
		relevantEdges.add(SDGEdge.Kind.DATA_DEP);
//		relevantEdges.add(SDGEdge.Kind.DATA_HEAP);
//		relevantEdges.add(SDGEdge.Kind.DATA_LOOP);
//		relevantEdges.add(SDGEdge.Kind.DATA_DEP_EXPR_VALUE);
//		relevantEdges.add(SDGEdge.Kind.DATA_DEP_EXPR_REFERENCE);
//		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_COND);
//		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_UNCOND);
//		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_EXPR);
//		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_CALL);
//		relevantEdges.add(SDGEdge.Kind.JUMP_DEP);
//		relevantEdges.add(SDGEdge.Kind.SUMMARY);
		relevantEdges.add(SDGEdge.Kind.SUMMARY_DATA);
//		relevantEdges.add(SDGEdge.Kind.SYNCHRONIZATION);

		return compute(pack, SDGEdge.Kind.SUMMARY_DATA, relevantEdges, progress);
	}

	public static int computeFullAliasDataDep(WorkPackage<SDG> pack, IProgressMonitor progress) throws CancelException {
		return compute(pack, progress);
	}

	public static int computeNoAliasDataDep(WorkPackage<SDG> pack, IProgressMonitor progress) throws CancelException {
		Set<SDGEdge.Kind> relevantEdges = new HashSet<SDGEdge.Kind>();
		relevantEdges.add(SDGEdge.Kind.DATA_DEP);
		relevantEdges.add(SDGEdge.Kind.DATA_HEAP);
//		relevantEdges.add(SDGEdge.Kind.DATA_ALIAS);
		relevantEdges.add(SDGEdge.Kind.DATA_LOOP);
		relevantEdges.add(SDGEdge.Kind.DATA_DEP_EXPR_VALUE);
		relevantEdges.add(SDGEdge.Kind.DATA_DEP_EXPR_REFERENCE);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_COND);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_UNCOND);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_EXPR);
		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_CALL);
		relevantEdges.add(SDGEdge.Kind.JUMP_DEP);
//		relevantEdges.add(SDGEdge.Kind.SUMMARY);
		relevantEdges.add(SDGEdge.Kind.SUMMARY_DATA);
		relevantEdges.add(SDGEdge.Kind.SUMMARY_NO_ALIAS);
		relevantEdges.add(SDGEdge.Kind.SYNCHRONIZATION);

		return compute(pack, SDGEdge.Kind.SUMMARY_NO_ALIAS, relevantEdges, progress);
	}

	public static int computeHeapDataDep(WorkPackage<SDG> pack, IProgressMonitor progress) throws CancelException {
		// default summary computation follows control and date dependencies
		Set<SDGEdge.Kind> relevantEdges = new HashSet<SDGEdge.Kind>();
		relevantEdges.add(SDGEdge.Kind.DATA_DEP);
		relevantEdges.add(SDGEdge.Kind.DATA_HEAP);
		relevantEdges.add(SDGEdge.Kind.DATA_ALIAS);
//		relevantEdges.add(SDGEdge.Kind.DATA_LOOP);
//		relevantEdges.add(SDGEdge.Kind.DATA_DEP_EXPR_VALUE);
//		relevantEdges.add(SDGEdge.Kind.DATA_DEP_EXPR_REFERENCE);
//		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_COND);
//		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_UNCOND);
//		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_EXPR);
//		relevantEdges.add(SDGEdge.Kind.CONTROL_DEP_CALL);
//		relevantEdges.add(SDGEdge.Kind.JUMP_DEP);
//		relevantEdges.add(SDGEdge.Kind.SUMMARY);
		relevantEdges.add(SDGEdge.Kind.SUMMARY_DATA);
//		relevantEdges.add(SDGEdge.Kind.SUMMARY_NO_ALIAS);
//		relevantEdges.add(SDGEdge.Kind.SYNCHRONIZATION);

		return compute(pack, SDGEdge.Kind.SUMMARY_DATA, relevantEdges, progress);
	}

	private static int compute(WorkPackage<SDG> pack, SDGEdge.Kind sumEdgeKind, Set<SDGEdge.Kind> relevantEdges,
			IProgressMonitor progress) throws CancelException {
		return compute(pack, sumEdgeKind, relevantEdges, null, progress);
	}

	private static int compute(WorkPackage<SDG> pack, SDGEdge.Kind sumEdgeKind, Set<SDGEdge.Kind> relevantEdges,
			String annotate, IProgressMonitor progress) throws CancelException {
		SummaryComputation3<SDG> comp = new SummaryComputation3<SDG>(pack.getGraph(), pack.getAllFormalInIds(),
				pack.getRelevantProcIds(), pack.getFullyConnected(), pack.getOut2In(),
				pack.getRememberReached(), sumEdgeKind, relevantEdges, annotate);
		Collection<SDGEdge> formInOutSummaryEdge = comp.computeSummaryEdges(progress);

		for (SDGEdge edge : formInOutSummaryEdge) {
			pack.addSummaryDep(edge.getSource().getId(), edge.getTarget().getId());
		}

		// set work package to immutable and sort summary edges
		pack.workIsDone();

		return formInOutSummaryEdge.size();
	}

	@SuppressWarnings("serial")
	private static class PathEdgeReachedNodesBitvector extends BitVector {
		PathEdgeReachedNodesBitvector(int nbits) {
			super(nbits);
		}
	}
	
	@SuppressWarnings("serial")
	private static class AoPathsNodesBitvector extends BitVector {
		AoPathsNodesBitvector(int nbits) {
			super(nbits);
		}
	}
	
	
	private DirectedGraph<Integer, DefaultEdge> extractCallGraph(G graph) {
		final DirectedGraph<Integer, DefaultEdge> ret = new DefaultDirectedGraph<>(DefaultEdge.class);
		for (final SDGNode n : graph.vertexSet()) {
			final int proc = n.getProc(); 
			ret.addVertex(proc);
			if (n.getKind() == SDGNode.Kind.CALL) {
				for (final SDGEdge callEdge : graph.outgoingEdgesOf(n)) {
					if (callEdge.getKind() != SDGEdge.Kind.CALL) continue;
					final int calledProc = callEdge.getTarget().getProc(); 
					ret.addVertex(calledProc);
					ret.addEdge(proc, calledProc);
				}
			}
		}
		return ret;
	}

    private Collection<SDGEdge> computeSummaryEdges(IProgressMonitor progress) throws CancelException {
    	boolean assertionsEnabled = false;
    	assert (assertionsEnabled = true);
    	
    	HashSet<SDGEdge> formInOutSummaryEdge = new HashSet<SDGEdge>();

    	Map<Integer, Set<SDGNode>> proc2nodes = new HashMap<>();
        for (SDGNode n : (Set<SDGNode>) graph.vertexSet()) {
            proc2nodes.compute(n.getProc(), (k, nodes) -> {
            	if (nodes == null) {
            		nodes = new HashSet<>();
            	}
            	nodes.add(n);
            	return nodes;
            });
        }
        
        for (Entry<Integer, Set<SDGNode>> entry : proc2nodes.entrySet()) {
        	final int procedure = entry.getKey();
        	final Set<SDGNode> nodes = entry.getValue();
        	final SDGNode[] procLocal2Node = new SDGNode[nodes.size()];
			procLocalNodeId2Node.put(procedure, procLocal2Node);
        	int procLocalNodeId = 0;
        	for (SDGNode n : nodes) {
        		nodeId2ProcLocalNodeId.put(n.getId(), procLocalNodeId);
        		procLocal2Node[procLocalNodeId] =  n;
        		procLocalNodeId++;
        	}
        }
        
        procLocalNodeId2Node.trimToSize();
        nodeId2ProcLocalNodeId.trimToSize();
        
        for (SDGNode n : (Set<SDGNode>) graph.vertexSet()) {
            if (n.getKind() == SDGNode.Kind.FORMAL_OUT || n.getKind() == SDGNode.Kind.EXIT) {
            	if (relevantProcs != null && !relevantProcs.contains(n.getProc())) {
            		continue;
            	}

            	if (fullyConnected != null && fullyConnected.contains(n.getId())) {
            		continue;
            	}

                assert pathEdge.add(new Edge(n,n));
                assert n.customData == null || (!(n.customData instanceof RememberReachedBitVector));
                n.customData = new PathEdgeReachedNodesBitvector(proc2nodes.get(n.getProc()).size());
                worklists.compute(n.getProc(), (proc, workList) -> {
                	if (workList == null) {
                		workList = new IntrusiveList<>();
                	}
                	workList.add(new Edge(n,n));
                	return workList;
                });
                procedureWorkSet.add(n.getProc());
            }
            
            if (n.getKind() == SDGNode.Kind.ACTUAL_OUT) {
                assert n.customData == null || (!(n.customData instanceof RememberReachedBitVector));
                n.customData = new AoPathsNodesBitvector(proc2nodes.get(n.getProc()).size());
            }
        }
        
        proc2nodes = null;
        
        assert workListsConsistent();

        while (!procedureWorkSet.isEmpty()) {
        	
        	
            final int procedure; {
                final Iterator<Integer> iterator = procedureWorkSet.iterator();
                procedure = iterator.next();
                iterator.remove();
            }
            final IntrusiveList<Edge> worklist = worklists.get(procedure);
            current = worklist;
            while (!worklist.isEmpty()) {
            	MonitorUtil.throwExceptionIfCanceled(progress);

            	Edge next = worklist.poll();
            	SDGNode.Kind k = next.source.getKind();

            	switch(k) {
            	case ACTUAL_OUT:
            		if (fullyConnected != null && fullyConnected.contains(next.source.getId())) {
            			propagateAllActIns(worklist, next.source, next.target);
            		} else {
            			for (SDGEdge e : graph.incomingEdgesOfUnsafe(next.source)) {
            				final SDGEdge.Kind kind = e.getKind();
            				if (kind == sumEdgeKind
            						|| ((kind == SDGEdge.Kind.DATA_DEP || kind == SDGEdge.Kind.DATA_HEAP
            						|| kind == SDGEdge.Kind.DATA_ALIAS) && relevantEdges.contains(kind))
            						|| (kind == SDGEdge.Kind.CONTROL_DEP_EXPR
            						&& e.getSource().getKind() == SDGNode.Kind.CALL)) {
            					propagate(worklist, e.getSource(), next.target);
            				}
            			}
            		}
            		break;

            	case FORMAL_IN:
            		// next.source is relevant formal in then:
            		if (relevantFormalIns.contains(next.source.getId())) {
            			SDGEdge fInOut;
            			if (annotate != null && !annotate.isEmpty()) {
            				fInOut =  new LabeledSDGEdge(next.source, next.target, sumEdgeKind, annotate);
            			} else {
            				fInOut = sumEdgeKind.newEdge(next.source, next.target);
            			}

            			formInOutSummaryEdge.add(fInOut);
            		}

            		Collection<Edge> aiaoPairs = aiaoPairs(next);
            		for (Edge e : aiaoPairs) {
            			if (e.source == null || e.target == null) continue;

            			final SDGNode source = e.source;
            			final SDGNode target = e.target;
            			SDGEdge sum;
            			if (annotate != null && !annotate.isEmpty()) {
            				sum =  new LabeledSDGEdge(source, target, sumEdgeKind, annotate);
            			} else {
            				sum = sumEdgeKind.newEdge(source, target);
            			}
            			
            			boolean connectedInPDG = false;
            			if (assertionsEnabled) {
            				connectedInPDG = graph.containsEdge(source, target, eOut -> eOut.getKind().isSDGEdge());
            			}
            			
            			if (graph.addEdgeUnsafe(source, target, sum)) {
            				assert !connectedInPDG;
            				final AoPathsNodesBitvector aoPaths = (AoPathsNodesBitvector) target.customData;
            				if (!aoPaths.isZero()) {
            					final int caller = source.getProc();
            					procedureWorkSet.add(caller);
            					final IntrusiveList<Edge> workListInCaller = worklists.get(caller);
            		            final SDGNode[] procLocal2Node = procLocalNodeId2Node.get(caller);
            					
            					int procLocalId = - 1; 
            					while ((procLocalId = aoPaths.nextSetBit(procLocalId + 1)) != -1) {
            						SDGNode aoPathTarget = procLocal2Node[procLocalId];
            						propagate(workListInCaller, source, aoPathTarget);
            					}
            				}
            			} else {
            				assert connectedInPDG;
            			}
            		}
            		for (SDGEdge e : graph.incomingEdgesOfUnsafe(next.source)) {
            			final SDGEdge.Kind kind = e.getKind();
            			if ((kind == SDGEdge.Kind.DATA_DEP || kind == SDGEdge.Kind.DATA_HEAP
            					|| kind == SDGEdge.Kind.DATA_ALIAS) && relevantEdges.contains(kind)) {
            				propagate(worklist, e.getSource(), next.target);
            			}
            		}
            		break;

            	case ACTUAL_IN:
            		if (rememberReached) {
            			BitVector bv = (RememberReachedBitVector) next.source.customData;
            			int id = next.target.tmp;

            			if (bv.contains(id)) {
            				continue;
            			}

            			bv.set(id);
            		}

            		for (SDGEdge e : graph.incomingEdgesOfUnsafe(next.source)) {
            			if (e.getKind() == SDGEdge.Kind.CONTROL_DEP_EXPR) {
            				if (e.getSource().getKind() == SDGNode.Kind.CALL) {
            					propagate(worklist, e.getSource(), next.target);
            				}

            			} else if (relevantEdges.contains(e.getKind())) {
            				propagate(worklist, e.getSource(), next.target);
            			}
            		}

            		break;

            	case FORMAL_OUT:
            	case EXIT:
            		if (fullyConnected != null && fullyConnected.contains(next.source.getId())) {
            			propagateAllActIns(worklist, next.source, next.target);
            		} else {
            			for (SDGEdge e : graph.incomingEdgesOfUnsafe(next.source)) {
            				if (e.getKind() == SDGEdge.Kind.CONTROL_DEP_EXPR) {
            					if (e.getSource().getKind() == SDGNode.Kind.ENTRY) {
            						propagate(worklist, e.getSource(), next.target);
            					}

            				} else if (relevantEdges.contains(e.getKind())) {
            					propagate(worklist, e.getSource(), next.target);
            				}
            			}
            		}
            		//                    for (SDGEdge e : graph.incomingEdgesOf(next.source)) {
            		//                        if (e.getKind() == SDGEdge.Kind.CONTROL_DEP_EXPR) {
            		//                            if (e.getSource().getKind() == SDGNode.Kind.ENTRY) {
            		//                                propagate(new Edge(e.getSource(), next.target));
            		//                            }
            		//
            		//                        } else if (e.getKind().isIntraSDGEdge()) {
            		//                            propagate(new Edge(e.getSource(), next.target));
            		//                        }
            		//                    }
            		break;

            	default:
            		for (SDGEdge e : graph.incomingEdgesOfUnsafe(next.source)) {
            			if (relevantEdges.contains(e.getKind())) {
            				propagate(worklist, e.getSource(), next.target);
            			}
            		}
            		break;
            	}
            }
            
            // TODO: somehow update this implicitly when creating summary edges
            boolean leftScc = true;
            for (Integer inSameScc : procSccs.get(indexNumberOf.getInt(procedure))) {
            	if (procedureWorkSet.contains(inSameScc)) {
            		leftScc = false;
            		break;
            	}
            }

            // clear HashSet<SDGNode> at each node whenever we leave a scc
            if (leftScc) {
                for (Integer inSameScc : procSccs.get(indexNumberOf.getInt(procedure))) {
                	for (SDGNode n : procLocalNodeId2Node.get(inSameScc)) {
                		if (n.getKind() == SDGNode.Kind.FORMAL_OUT || n.getKind() == SDGNode.Kind.EXIT) {
                			if (relevantProcs != null && !relevantProcs.contains(n.getProc())) {
                				continue;
                			}

                			if (fullyConnected != null && fullyConnected.contains(n.getId())) {
                				continue;
                			}

                			assert n.customData instanceof PathEdgeReachedNodesBitvector;
                			n.customData = null;
                		}

                		if (n.getKind() == SDGNode.Kind.ACTUAL_OUT) {
                			assert n.customData instanceof AoPathsNodesBitvector;
                			n.customData = null;
                		}
                	}
                	procLocalNodeId2Node.remove(inSameScc);
                }
            }
            
            assert workListsConsistent();
        }

        return formInOutSummaryEdge;
    }
    
    private boolean workListsConsistent() {
        for (Entry<Integer, IntrusiveList<Edge>> entry : worklists.entrySet()) {
        	final Integer proc = entry.getKey(); 
        	final IntrusiveList<Edge> workList = entry.getValue();
        	if (!workList.isEmpty() &&  !procedureWorkSet.contains(proc)) {
        		return false;
        	}
        }
        return true;
    }

    private void propagateAllActIns(IntrusiveList<Edge> worklist, SDGNode outNode, SDGNode target) {
    	for (SDGNode inNode : out2in.get(outNode.getId())) {
    		propagate(worklist, inNode, target);
    	}
    }

    private void propagate(IntrusiveList<Edge> worklist, SDGNode source, SDGNode target) {
    	assert source.getProc() == target.getProc();
    	assert worklist == worklists.get(source.getProc());
        if (relevantProcs != null && !(relevantProcs.contains(source.getProc())
        		&& relevantProcs.contains(target.getProc()))) {
            return;
        }

//    	if (fullyConnected != null && fullyConnected.contains(e.target.getId())) {
//    		return;
//    	}
//
        
        if (pathEdge_add(source, target)) {
        	final Edge e = new Edge(source, target);
        	assert pathEdge.add(e);
            worklist.add(e);
            assert procedureWorkSet.contains(source.getProc()) || worklist == current;
            if (source.getKind() == SDGNode.Kind.ACTUAL_OUT) {
            	assert source.customData == null || source.customData instanceof AoPathsNodesBitvector;
            	final AoPathsNodesBitvector aoPaths = (AoPathsNodesBitvector) source.customData;
            	final int procLocalTargetId = nodeId2ProcLocalNodeId.getInt(target.getId());

            	aoPaths.set(procLocalTargetId);
            }
        } else {
        	assert pathEdge.contains(new Edge(source, target));
        }
    }
    
    private boolean pathEdge_add(SDGNode source, SDGNode target) {
    	assert source.getProc() == target.getProc();
		final PathEdgeReachedNodesBitvector sources = (PathEdgeReachedNodesBitvector) target.customData;
    	final int procLocalSourceId = nodeId2ProcLocalNodeId.getInt(source.getId());
    	return sources.setWithResult(procLocalSourceId);
    }

//    if (relevantProcs != null) {
//    	if (!(relevantProcs.contains(e.source.getProc())
//    		&& relevantProcs.contains(e.target.getProc()))) {
//    		continue;
//    	} else if (graph.containsEdge(e.source, e.target)) {
//    		continue;
//    	}
//    }



    private Collection<Edge> aiaoPairs(Edge e) {
        HashMap<SDGNode, Edge> result = new HashMap<SDGNode, Edge>();

        for (SDGEdge pi : graph.incomingEdgesOfUnsafe(e.source)) {
            if (pi.getKind() == SDGEdge.Kind.PARAMETER_IN) {
                SDGNode ai = pi.getSource();

//                if (relevantProcs != null && !relevantProcs.contains(ai.getProc())) {
//            		continue;
//                }


                SDGNode call = getCallSiteFor(ai);

                if(call != null) {
                    result.put(call, new Edge(ai, null));
                }
            }
        }

        for (SDGEdge po : graph.outgoingEdgesOfUnsafe(e.target)) {
            if (po.getKind() == SDGEdge.Kind.PARAMETER_OUT) {
                SDGNode ao = po.getTarget();

//                if (relevantProcs != null && !relevantProcs.contains(ao.getProc())) {
//            		continue;
//                }

                SDGNode call = getCallSiteFor(ao);

                Edge newE = result.get(call);
                if (newE != null) {
                	
                    newE.target = ao;
                    assert newE.target.getProc() == newE.source.getProc();
                }
            }
        }

        return result.values();
    }

    private SDGNode getCallSiteForSlow(SDGNode node){
        if (node.getKind() == SDGNode.Kind.ACTUAL_IN) {
            SDGNode n = node;

            while (true){
                // follow control-dependence-expression edges from the source
                // node of 'edge' to the call node
                for(SDGEdge e : graph.incomingEdgesOfUnsafe(n)){
                    if(e.getKind() == SDGEdge.Kind.CONTROL_DEP_EXPR){
                        if(e.getSource().getKind() == SDGNode.Kind.CALL){
                            return e.getSource();
                        }
                        n = e.getSource();
                        break;
                    }
                }
            }

        } else if (node.getKind() == SDGNode.Kind.ACTUAL_OUT){
            SDGNode n = node;

            while(true){
                // follow control-dependence-expression
                // edges from 'node' to the call node
                for(SDGEdge e : graph.incomingEdgesOfUnsafe(n)){
                    if(e.getKind() == SDGEdge.Kind.CONTROL_DEP_EXPR){
                        if(e.getSource().getKind() == SDGNode.Kind.CALL){
                            return e.getSource();
                        }
                        n = e.getSource();
                        break;
                    }
                }
            }

        }
        return null;
    }
    
    private SDGNode getCallSiteFor(SDGNode node){
    	assert node.getKind() == SDGNode.Kind.ACTUAL_IN || node.getKind() == SDGNode.Kind.ACTUAL_OUT;

    	for(SDGEdge e : graph.incomingEdgesOfUnsafe(node)){
    		if(e.getKind() == SDGEdge.Kind.CONTROL_DEP_EXPR){
    			assert e.getSource().getKind() == SDGNode.Kind.CALL; 
    			assert e.getSource().equals(getCallSiteForSlow(node));
    			return e.getSource();
    		}
    	}
        assert getCallSiteForSlow(node) == null;
        return null;
    }


    private static class Edge implements Intrusable<Edge> {
        private SDGNode source;
        private SDGNode target;
        
        private Edge next;

        private Edge(SDGNode s, SDGNode t) {
        	assert t == null || t.getKind() == SDGNode.Kind.FORMAL_OUT || t.getKind() == SDGNode.Kind.EXIT;
        	assert t == null || s.getProc() == t.getProc();
            source = s;
            target = t;
        }
        
        @Override
        public void setNext(Edge next) {
        	this.next = next;
        }
        
        @Override
        public Edge getNext() {
        	return next;
        }
        

        public boolean equals(Object o) {
            if (o instanceof Edge) {
                Edge e = (Edge) o;
                return (e.source == source && e.target == target);
            } else {
                return false;
            }
        }

        @Deprecated
        @SuppressWarnings("unused")
        public int hashCodeOld() {
            return source.getId() | target.getId() << 16;
        }
        
        @Override
        public int hashCode() {
        	return (source.hashCode() ^ (Integer.rotateRight(target.hashCode(), 16)));
        }

        public String toString() {
            return source.getId()+" -> "+target.getId();
        }
    }

}

class SummaryComputer3 implements ISummaryComputer {
	@Override
	public int compute(WorkPackage<SDG> pack, boolean parallel, IProgressMonitor progress) throws CancelException {
		return SummaryComputation3.compute(pack, progress);
	}

	@Override
	public int computeAdjustedAliasDep(WorkPackage<SDG> pack, boolean parallel, IProgressMonitor progress)
			throws CancelException {
		return SummaryComputation3.computeAdjustedAliasDep(pack, progress);
	}

	@Override
	public int computePureDataDep(WorkPackage<SDG> pack, boolean parallel, IProgressMonitor progress)
			throws CancelException {
		return SummaryComputation3.computePureDataDep(pack, progress);
	}

	@Override
	public int computeFullAliasDataDep(WorkPackage<SDG> pack, boolean parallel, IProgressMonitor progress)
			throws CancelException {
		return SummaryComputation3.computeFullAliasDataDep(pack, progress);
	}

	@Override
	public int computeNoAliasDataDep(WorkPackage<SDG> pack, boolean parallel, IProgressMonitor progress)
			throws CancelException {
		return SummaryComputation3.computeNoAliasDataDep(pack, progress);
	}

	@Override
	public int computeHeapDataDep(WorkPackage<SDG> pack, boolean parallel, IProgressMonitor progress)
			throws CancelException {
		return SummaryComputation3.computeHeapDataDep(pack, progress);
	}
}