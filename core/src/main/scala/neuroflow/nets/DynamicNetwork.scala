package neuroflow.nets

import breeze.linalg._
import breeze.numerics._
import breeze.stats._
import neuroflow.core.Network._
import neuroflow.core._

import scala.annotation.tailrec

/**
  *
  * Same as DefaultNetwork, but it uses the Armijo–Goldstein condition
  * to adapt the learning rate to an optimal value. This promises faster convergence,
  * but comes with more computational overhead than gradient descent.
  *
  * Here, the learning parameter should be a large starting value.
  *
  * @author bogdanski
  * @since 20.01.16
  *
  */


object DynamicNetwork {
  implicit val constructor: Constructor[DynamicNetwork] = new Constructor[DynamicNetwork] {
    def apply(ls: Seq[Layer], settings: Settings)(implicit weightProvider: WeightProvider): DynamicNetwork = {
      DynamicNetwork(ls, settings, weightProvider(ls))
    }
  }
}


private[nets] case class DynamicNetwork(layers: Seq[Layer], settings: Settings, weights: Weights) extends FeedForwardNetwork with EarlyStoppingLogic {

  /**
    * Takes a sequence of input vectors `xs` and trains this
    * feed forward network against the corresponding output vectors `ys`.
    */
  def train(xs: Seq[Seq[Double]], ys: Seq[Seq[Double]]): Unit = {
    import settings._
    run(xs, ys, learningRate, precision, 0, maxIterations)
  }

  /**
    * Takes the input vector `x` to compute their output.
    */
  def evaluate(xs: Seq[Double]): Seq[Double] = {
    val input = DenseMatrix.create[Double](1, xs.size, xs.toArray)
    flow(input, 0, layers.size - 1).toArray.toVector
  }

  /**
    * Trains this `Network` with optimal weights based on `xs` and `ys`
    */
  @tailrec private def run(xs: Seq[Seq[Double]], ys: Seq[Seq[Double]], stepSize: Double, precision: Double,
                           iteration: Int, maxIterations: Int): Unit = {
    val input = xs map (x => DenseMatrix.create[Double](1, x.size, x.toArray))
    val output = ys map (y => DenseMatrix.create[Double](1, y.size, y.toArray))
    val error = errorFunc(input, output)
    if ((mean(error) > precision) && iteration < maxIterations && !shouldStopEarly) {
      if (settings.verbose) info(s"Taking step $iteration - error: $error, error per sample: ${sum(error) / input.size}")
      adaptWeights(input, output, stepSize)
      run(xs, ys, stepSize, precision, iteration + 1, maxIterations)
    } else {
      if (settings.verbose) info(s"Took $iteration iterations of $maxIterations with error $error")
    }
  }

  /**
    * Computes gradient via `errorFuncDerivative` for all weights,
    * and adapts their value using gradient descent.
    */
  private def adaptWeights(xs: Seq[DenseMatrix[Double]], ys: Seq[DenseMatrix[Double]], stepSize: Double): Unit = {
    weights.foreach { l =>
      l.foreachPair { (k, v) =>
        val weightLayer = weights.indexOf(l)
        val firstOrder = if (settings.approximation.isDefined) approximateErrorFuncDerivative(xs, ys, weightLayer, k) else errorFuncDerivative(xs, ys, weightLayer, k)
        val direction = mean(-firstOrder)
        l.update(k, v + α(stepSize, direction, xs, ys, weightLayer, k) * direction)
      }
    }
  }

  /**
    * Computes the network recursively from `cursor` until `target` (both representing the 'layer indices')
    */
  @tailrec private def flow(in: DenseMatrix[Double], cursor: Int, target: Int): DenseMatrix[Double] = {
    if (target < 0) in
    else {
      val processed = layers(cursor) match {
        case h: HasActivator[Double] =>
          if (cursor <= (weights.size - 1)) in.map(h.activator) * weights(cursor)
          else in.map(h.activator)
        case _ => in * weights(cursor)
      }
      if (cursor < target) flow(processed, cursor + 1, target) else processed
    }
  }

  /**
    * Evaluates the error function Σ1/2(prediction(x) - observation)²
    */
  private def errorFunc(xs: Seq[DenseMatrix[Double]], ys: Seq[DenseMatrix[Double]]): DenseMatrix[Double] = {
    xs.zip(ys).par.map { t =>
      val (x, y) = t
      0.5 * pow(flow(x, 0, layers.size - 1) - y, 2)
    }.reduce(_ + _)
  }

  /**
    * Computes the error function derivative with respect to `weight` in `weightLayer`
    */
  private def errorFuncDerivative(xs: Seq[DenseMatrix[Double]], ys: Seq[DenseMatrix[Double]],
                              weightLayer: Int, weight: (Int, Int)): DenseMatrix[Double] = {
    xs.zip(ys).map { t =>
      val (x, y) = t
      val ws = weights.map(_.copy)
      ws(weightLayer).update(weight, 1.0)
      ws(weightLayer).foreachKey(k => if (k != weight) ws(weightLayer).update(k, 0.0))
      val in = flow(x, 0, weightLayer - 1).map { i =>
        layers(weightLayer) match {
          case h: HasActivator[Double] => h.activator(i)
          case _ => i
        }
      }
      val ds = layers.drop(weightLayer + 1).map {
        case h: HasActivator[Double] =>
          val i = layers.indexOf(h) - 1
          flow(x, 0, i).map(h.activator.derivative)
      }
      (flow(x, 0, layers.size - 1) - y) :* chain(ds, ws, in, weightLayer, 0)
    }.reduce(_ + _)
  }

  /**
    * Constructs overall chain rule derivative based on single derivatives `ds` recursively.
    */
  @tailrec private def chain(ds: Seq[DenseMatrix[Double]], ws: Seq[DenseMatrix[Double]], in: DenseMatrix[Double], cursor: Int, cursorDs: Int): DenseMatrix[Double] = {
    if (cursor < ws.size - 1) chain(ds, ws, ds(cursorDs) :* (in * ws(cursor)), cursor + 1, cursorDs + 1) else ds(cursorDs) :* (in * ws(cursor))
  }

  /**
    * Approximates the gradient based on finite central differences.
    */
  private def approximateErrorFuncDerivative(xs: Seq[DenseMatrix[Double]], ys: Seq[DenseMatrix[Double]],
                                         layer: Int, weight: (Int, Int)): DenseMatrix[Double] = {
    finiteCentralDiff(xs, ys, layer, weight, order = 1)
  }

  /**
    * Approximates the second gradient based on finite central differences.
    */
  private def approximateErrorFuncDerivativeSecond(xs: Seq[DenseMatrix[Double]], ys: Seq[DenseMatrix[Double]],
                                                        layer: Int, weight: (Int, Int)): DenseMatrix[Double] = {
    finiteCentralDiff(xs, ys, layer, weight, order = 2)
  }

  /**
    * Computes the finite central diff for respective `order`.
    */
  private def finiteCentralDiff(xs: Seq[DenseMatrix[Double]], ys: Seq[DenseMatrix[Double]],
                                layer: Int, weight: (Int, Int), order: Int): DenseMatrix[Double] = {
    val Δ = settings.approximation.getOrElse(Approximation(0.000001)).Δ
    val f = () => if (order == 1) errorFunc(xs, ys) else approximateErrorFuncDerivative(xs, ys, layer, weight)
    val v = weights(layer)(weight)
    weights(layer).update(weight, v - Δ)
    val a = f.apply
    weights(layer).update(weight, v + Δ)
    val b = f.apply
    weights(layer).update(weight, v)
    (b - a) / (2 * Δ)
  }

  /**
    * Tries to find the optimal step size α through backtracking line search.
    */
  @tailrec private def α(stepSize: Double, direction: Double, xs: Seq[DenseMatrix[Double]], ys: Seq[DenseMatrix[Double]],
                weightLayer: Int, weight: (Int, Int)): Double = {
    val v = weights(weightLayer)(weight)
    val (τ, c) = settings.specifics.map(s => (s("τ"), s("c"))).getOrElse((0.5, 0.5))
    val t = -c * (-direction * direction)
    val a = mean(errorFunc(xs, ys))
    weights(weightLayer).update(weight, v + (stepSize * direction))
    val b = mean(errorFunc(xs, ys))
    weights(weightLayer).update(weight, v)
    if ((a - b) < (stepSize * t)) α(stepSize * τ, direction, xs, ys, weightLayer, weight) else stepSize
  }

}
