package org.change.v2.analysis.memory

import org.change.v2.analysis.processingmodels.State

/**
 * Author: Radu Stoenescu
 * Don't be a stranger,  symnetic.7.radustoe@spamgourmet.com
 */
trait Intable extends ((State) => Option[Int])