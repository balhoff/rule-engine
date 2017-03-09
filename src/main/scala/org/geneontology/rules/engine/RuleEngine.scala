package org.geneontology.rules.engine

import scala.collection.mutable

import scalaz._
import scalaz.Scalaz._
import scala.collection.immutable.Queue

final class RuleEngine(inputRules: Iterable[Rule], val storeDerivations: Boolean) {

  val rules = inputRules.toSet
  private val alphaIndex: Map[TriplePattern, AlphaNode] = processRules(rules)

  private def processRules(rules: Iterable[Rule]): Map[TriplePattern, AlphaNode] = {
    val alphaNodeIndex: mutable.Map[TriplePattern, AlphaNode] = mutable.AnyRefMap.empty
    val joinIndex: mutable.Map[List[TriplePattern], JoinNode] = mutable.AnyRefMap.empty
    def processRulePatterns(patterns: List[TriplePattern], parent: BetaNode, parentPatterns: List[TriplePattern], rule: Rule): Unit = patterns match {
      case pattern :: rest => {
        val blankPattern = pattern.blankVariables
        val alphaNode = alphaNodeIndex.getOrElseUpdate(blankPattern, new AlphaNode(blankPattern))
        val thisPatternSequence = pattern :: parentPatterns
        val joinNode = joinIndex.getOrElseUpdate(thisPatternSequence, new JoinNode(parent, alphaNode, thisPatternSequence))
        parent.addChild(joinNode)
        alphaNode.addChild(joinNode)
        processRulePatterns(rest, joinNode, thisPatternSequence, rule)
      }
      case Nil => {
        val pNode = new ProductionNode(rule, parent, this)
        parent.addChild(pNode)
      }
    }
    for { rule <- rules } {
      processRulePatterns(rule.body, BetaRoot, Nil, rule)
    }
    val parentsMap = joinIndex.values.map(j => j -> parentPath(j)).toMap
    for {
      alpha <- alphaNodeIndex.values
    } {
      alpha.orderChildren(parentsMap)
    }
    alphaNodeIndex.toMap
  }

  private def parentPath(joinNode: JoinNode): Set[JoinNode] = {
    val parent = joinNode.leftParent
    if (parent.isInstanceOf[JoinNode]) {
      parentPath(parent.asInstanceOf[JoinNode]) + parent.asInstanceOf[JoinNode]
    } else Set.empty
  }

  def processTriples(triples: Iterable[Triple]): WorkingMemory = {
    val memory = new WorkingMemory(triples.toSet)
    memory.agenda = Queue(triples.toSeq: _*)
    while (memory.agenda.nonEmpty) {
      val (triple, rest) = memory.agenda.dequeue
      memory.agenda = rest
      injectTriple(triple, memory)
    }
    memory
  }

  private val DegeneratePattern = TriplePattern(AnyNode, AnyNode, AnyNode)

  protected[engine] def processTriple(triple: Triple, memory: WorkingMemory): Unit = {
    if (!memory.facts(triple)) {
      memory.facts += triple
      memory.agenda = memory.agenda.enqueue(triple)
    }

  }

  protected[engine] def processDerivedTriple(triple: Triple, derivation: Derivation, memory: WorkingMemory) = {
    if (!memory.facts(triple)) {
      memory.facts += triple
      memory.derivations = memory.derivations |+| Map(triple -> List(derivation))
      memory.agenda = memory.agenda.enqueue(triple)
    }
  }

  private def injectTriple(triple: Triple, memory: WorkingMemory): Unit = {
    val patterns = List(DegeneratePattern,
      TriplePattern(AnyNode, AnyNode, triple.o),
      TriplePattern(AnyNode, triple.p, AnyNode),
      TriplePattern(AnyNode, triple.p, triple.o),
      TriplePattern(triple.s, AnyNode, AnyNode),
      TriplePattern(triple.s, AnyNode, triple.o),
      TriplePattern(triple.s, triple.p, AnyNode),
      TriplePattern(triple.s, triple.p, triple.o))
    for {
      pattern <- patterns
      alphaNode <- alphaIndex.get(pattern)
    } alphaNode.activate(triple, memory)
  }

}
