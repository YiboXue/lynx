package org.grapheco.lynx.dataframe

import org.grapheco.lynx.LynxType
import org.grapheco.lynx.evaluator.{ExpressionContext, ExpressionEvaluator}
import org.grapheco.lynx.types.LynxValue
import org.grapheco.lynx.util.Profiler
import org.opencypher.v9_0.expressions.Expression

/**
 * @Author: Airzihao
 * @Description:
 * @Date: Created at 20:25 2022/7/5
 * @Modified By:
 */
class BasicDataFrameOperator(expressionEvaluator: ExpressionEvaluator) extends DataFrameOperator {
  /*

   */
  override def select(df: DataFrame, columns: Seq[(String, Option[String])]): DataFrame = {
    Profiler.timing("DF Select",
      {
        val sourceSchema: Map[String, LynxType] = df.schema.toMap
        val columnNameIndex: Map[String, Int] = df.columnsName.zipWithIndex.toMap
        val newSchema: Seq[(String, LynxType)] = columns.map(column => column._2.getOrElse(column._1) -> sourceSchema(column._1))
        val usedIndex: Seq[Int] = columns.map(_._1).map(columnNameIndex)

        DataFrame(newSchema, () => df.records.map(row => usedIndex.map(row.apply)))
      }
    )
  }

  override def filter(df: DataFrame, predicate: Seq[LynxValue] => Boolean)(ctx: ExpressionContext): DataFrame =
    Profiler.timing("DF Filter", DataFrame(df.schema, () => df.records.filter(predicate)))

  override def project(df: DataFrame, columns: Seq[(String, Expression)])(ctx: ExpressionContext): DataFrame = {
    Profiler.timing("DF Project ",
      {
        val newSchema: Seq[(String, LynxType)] = columns.map {
          case (name, expression) => name -> expressionEvaluator.typeOf(expression, df.schema.toMap)
        }

        DataFrame(newSchema,
          () => df.records.map(
            record => {
              val recordCtx = ctx.withVars(df.columnsName.zip(record).toMap)
              columns.map(col => expressionEvaluator.eval(col._2)(recordCtx)) //TODO: to opt
            }
          )
        )
      }
    )
  }


  override def groupBy(df: DataFrame, groupings: Seq[(String, Expression)], aggregations: Seq[(String, Expression)])(ctx: ExpressionContext): DataFrame = {
    Profiler.timing("DF GroupBy",
      {
        // match (n:nothislabel) return count(n)
        val newSchema = (groupings ++ aggregations).map(col =>
          col._1 -> expressionEvaluator.typeOf(col._2, df.schema.toMap)
        )
        val columnsName = df.columnsName

        DataFrame(newSchema, () => {
          if (groupings.nonEmpty) {
            df.records.map { record =>
              val recordCtx = ctx.withVars(columnsName.zip(record).toMap)
              groupings.map(col => expressionEvaluator.eval(col._2)(recordCtx)) -> recordCtx
            } // (groupingValue: Seq[LynxValue] -> recordCtx: ExpressionContext)
              .toSeq.groupBy(_._1) // #group by 'groupingValue'.
              .mapValues(_.map(_._2)) // #trans to: (groupingValue: Seq[LynxValue] -> recordsCtx: Seq[ExpressionContext])
              .map { case (groupingValue, recordsCtx) => // #aggragate: (groupingValues & aggregationValues): Seq[LynxValue]
                groupingValue ++ {
                  aggregations.map { case (name, expr) => expressionEvaluator.aggregateEval(expr)(recordsCtx) }
                }
              }.toIterator
          } else {
            val allRecordsCtx = df.records.map { record => ctx.withVars(columnsName.zip(record).toMap) }.toSeq
            Iterator(aggregations.map { case (name, expr) => expressionEvaluator.aggregateEval(expr)(allRecordsCtx) })
          }
        })
      }
    )
  }

  override def skip(df: DataFrame, num: Int): DataFrame =
    Profiler.timing("DF Skip", DataFrame(df.schema, () => df.records.drop(num)))

  override def take(df: DataFrame, num: Int): DataFrame = Profiler.timing("DF Take", DataFrame(df.schema, () => df.records.take(num)))

  override def join(a: DataFrame, b: DataFrame, joinColumns: Seq[String], joinType: JoinType): DataFrame = {
    Profiler.timing("DF Join", Joiner.join(a, b, joinColumns, joinType))
  }

  /*
  * @param: df is a DataFrame
  * @function: Remove the duplicated rows in the df.
  * */
  override def distinct(df: DataFrame): DataFrame = DataFrame(df.schema, () => df.records.toSeq.distinct.iterator)

  override def orderBy(df: DataFrame, sortItem: Seq[(Expression, Boolean)])(ctx: ExpressionContext): DataFrame = {
    Profiler.timing("DF Order By",
      {
        val columnsName = df.columnsName
        DataFrame(df.schema, () => df.records.toSeq
          .sortWith{ (A,B) =>
            val ctxA = ctx.withVars(columnsName.zip(A).toMap)
            val ctxB = ctx.withVars(columnsName.zip(B).toMap)
            val sortValue = sortItem.map{ case(exp, asc) =>
              (expressionEvaluator.eval(exp)(ctxA), expressionEvaluator.eval(exp)(ctxB), asc)
            }
            _lessThan(sortValue.toIterator)
          }.toIterator)
      }
    )
  }

  private def _lessThan(sortValue: Iterator[(LynxValue, LynxValue, Boolean)]): Boolean = {
    while (sortValue.hasNext) {
      val (valueOfA, valueOfB, asc) = sortValue.next()
      val oA = LynxValue.typeOrder(valueOfA)
      val oB = LynxValue.typeOrder(valueOfB)
      if (oA == oB){ // same type
        val comparable = valueOfA.compareTo(valueOfB)
        if(comparable != 0) return comparable > 0 != asc
      } else { // diff type
        return (oA > oB) != asc
      }
    }
    false
  }

}
