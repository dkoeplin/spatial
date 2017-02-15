// /*
//  Copyright (c) 2011, 2012, 2013, 2014 The University of Sydney.
//  All Rights Reserved.  Redistribution and use in source and
//  binary forms, with or without modification, are permitted
//  provided that the following conditions are met:
//     * Redistributions of source code must retain the above
//       copyright notice, this list of conditions and the following
//       two paragraphs of disclaimer.
//     * Redistributions in binary form must reproduce the above
//       copyright notice, this list of conditions and the following
//       two paragraphs of disclaimer in the documentation and/or other materials
//       provided with the distribution.
//     * Neither the name of the Regents nor the names of its contributors
//       may be used to endorse or promote products derived from this
//       software without specific prior written permission.
//  IN NO EVENT SHALL REGENTS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
//  SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
//  ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
//  REGENTS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//  REGENTS SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT
//  LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
//  A PARTICULAR PURPOSE. THE SOFTWARE AND ACCOMPANYING DOCUMENTATION, IF
//  ANY, PROVIDED HEREUNDER IS PROVIDED "AS IS". REGENTS HAS NO OBLIGATION
//  TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
//  MODIFICATIONS.
// */

package types

import chisel3._
import chisel3.internal.firrtl.BinaryPoint
import chisel3.util

// // Raw numbers
// class RawBits(b: Int) extends Bundle { 
// 	val raw = UInt(b.W)

// 	// Conversions
// 	def storeFix(dst: FixedPoint): Unit = { 
// 	  assert(dst.d + dst.f == b)
// 	  dst.number := raw
// 	}

// 	// Arithmetic

//   override def cloneType = (new RawBits(b)).asInstanceOf[this.type] // See chisel3 bug 358
// }

// // Fixed point numbers
// class FixedPoint(val s: Boolean, val d: Int, val f: Int) extends Bundle {
// 	// Overloaded
// 	def this(s: Int, d: Int, f: Int) = this(s == 1, d, f)
// 	def this(tuple: (Boolean, Int, Int)) = this(tuple._1, tuple._2, tuple._3)

// 	// Properties
// 	val number = UInt((d + f).W)
// 	val debug_overflow = Bool()

// 	// Conversions
// 	def storeRaw(dst: RawBits): Unit = {
// 		dst.raw := number
// 	}
// 	def cast(dst: FixedPoint, rounding: String = "truncate", saturating: String = "lazy"): Unit = {
// 		val new_width = dst.d + dst.f
// 		val new_raw = Wire(UInt(new_width.W))

// 		// Compute new frac part
// 		val new_frac = if (dst.f < f) { // shrink decimals
// 			rounding match {
// 				case "truncate" => 
// 					val shave = f - dst.f
// 					(0 until dst.f).map{ i => number(shave + i)*scala.math.pow(2,i).toInt.U }.reduce{_+_}
// 				case "unbiased" => 
// 					0.U(dst.f.W)
// 					// TODO: Add rng
// 				case "biased" =>
// 					0.U(dst.f.W)
// 					// TODO: force direction
// 				case _ =>
// 					0.U(dst.f.W)
// 					// TODO: throw error
// 			}
// 		} else if (dst.f > f) { // expand decimals
// 			val expand = dst.f - f
// 			(0 until dst.f).map{ i => if (i < expand) {0.U} else {number(i - expand)*scala.math.pow(2,i).toInt.U}}.reduce{_+_}
// 		} else { // keep same
// 			(0 until dst.f).map{ i => number(i)*scala.math.pow(2,i).toInt.U }.reduce{_+_}
// 		}

// 		// Compute new dec part
// 		val new_dec = if (dst.d < d) { // shrink decimals
// 			saturating match { 
// 				case "lazy" =>
// 					val shave = d - dst.d
// 					dst.debug_overflow := (0 until shave).map{i => number(d + f - 1 - i)}.reduce{_||_}
// 					(0 until dst.d).map{i => number(f + i) * scala.math.pow(2,i).toInt.U}.reduce{_+_}
// 				case "saturation" =>
// 					// TODO: Do something good
// 					0.U(dst.d.W)
// 				case _ =>
// 					0.U(dst.d.W)
// 			}
// 		} else if (dst.d > d) { // expand decimals
// 			val expand = dst.d - d
// 			val sgn_extend = if (s) { number(d+f-1) } else {0.U(1.W)}
// 			(0 until dst.d).map{ i => if (i >= dst.d - expand) {sgn_extend*scala.math.pow(2,i).toInt.U} else {number(i+f)*scala.math.pow(2,i).toInt.U }}.reduce{_+_}
// 		} else { // keep same
// 			(0 until dst.d).map{ i => number(i + f)*scala.math.pow(2,i).toInt.U }.reduce{_+_}
// 		}

// 		dst.number := new_frac + new_dec*(scala.math.pow(2,dst.f).toInt.U)

// 	}
	
// 	// Arithmetic
// 	def + (op: FixedPoint): FixedPoint = {
// 		// Compute upcasted type and return type
// 		val upcasted_type = (op.s | s, scala.math.max(op.d, d) + 1, scala.math.max(op.f, f))
// 		val return_type = (op.s | s, scala.math.max(op.d, d), scala.math.max(op.f, f))
// 		// Get upcasted operators
// 		val full_result = Wire(new FixedPoint(upcasted_type))
// 		// Do upcasted operation
// 		full_result.number := this.number + op.number
// 		// Downcast to result
// 		val result = Wire(new FixedPoint(return_type))
// 		full_result.cast(result)
// 		result
// 	}

// 	def - (op: FixedPoint): FixedPoint = {
// 		// Compute upcasted type and return type
// 		val upcasted_type = (op.s | s, scala.math.max(op.d, d) + 1, scala.math.max(op.f, f))
// 		val return_type = (op.s | s, scala.math.max(op.d, d), scala.math.max(op.f, f))
// 		// Get upcasted operators
// 		val full_result = Wire(new FixedPoint(upcasted_type))
// 		// Do upcasted operation
// 		full_result.number := this.number - op.number
// 		// Downcast to result
// 		val result = Wire(new FixedPoint(return_type))
// 		full_result.cast(result)
// 		result
// 	}

// 	def * (op: FixedPoint): FixedPoint = {
// 		// Compute upcasted type and return type
// 		val upcasted_type = (op.s | s, op.d + d, op.f + f)
// 		val return_type = (op.s | s, scala.math.max(op.d, d), scala.math.max(op.f, f))
// 		// Get upcasted operators
// 		val full_result = Wire(new FixedPoint(upcasted_type))
// 		// Do upcasted operation
// 		full_result.number := this.number << 64 >> 64 * op.number << 64 >> 64
// 		// Downcast to result
// 		val result = Wire(new FixedPoint(return_type))
// 		full_result.cast(result)
// 		result
// 	}

// 	def / (op: FixedPoint): FixedPoint = {
// 		// Compute upcasted type and return type
// 		val upcasted_type = (op.s | s, op.d + d, op.f + f + 1)
// 		val return_type = (op.s | s, scala.math.max(op.d, d), scala.math.max(op.f, f))
// 		// Get upcasted operators
// 		val full_result = Wire(new FixedPoint(upcasted_type))
// 		// Do upcasted operation
// 		full_result.number := this.number * scala.math.pow(2,op.f+f).toInt.U / op.number // Not sure why we need the +1 in pow2
// 		// Downcast to result
// 		val result = Wire(new FixedPoint(return_type))
// 		full_result.cast(result)
// 		result
// 	}



// 	// def * (op: FixedPoint): FixedPoint = {
// 	// 	// Compute upcasted type
// 	// 	val sign = op.s | s
// 	// 	val d_prec = op.d + d
// 	// 	val f_prec = op.f + f
// 	// 	// Do math on UInts
// 	// 	val r1 = Wire(new RawBits(d_prec + f_prec))
// 	// 	this.storeRaw(r1)
// 	// 	val r2 = Wire(new RawBits(d_prec + f_prec))
// 	// 	op.storeRaw(r2)
// 	// 	val rawResult = r1 * r2
// 	// 	// Store to FixedPoint result
// 	// 	val result = Wire(new FixedPoint(sign, scala.math.max(op.d, d), scala.math.max(op.f, f)))
// 	// 	rawResult.storeFix(result)
// 	// 	result.debug_overflow := Mux(rawResult.raw(0), true.B, false.B)
// 	// 	result
// 	// }

//     override def cloneType = (new FixedPoint(s,d,f)).asInstanceOf[this.type] // See chisel3 bug 358

// }

// Testing
class FixedPointTester(val s: Boolean, val d: Int, val f: Int) extends Module {
	def this(tuple: (Boolean, Int, Int)) = this(tuple._1, tuple._2, tuple._3)
	val io = IO( new Bundle {
		val num1 = UInt((d+f).W).asInput
		val num2 = UInt((d+f).W).asInput

		val add_result = UInt((d+f).W).asOutput
		val prod_result = UInt((d+f).W).asOutput
		val sub_result = UInt((d+f).W).asOutput
		val quotient_result = UInt((d+f).W).asOutput
	})

	// val fix1 = Wire(new FixedPoint(s,d,f))
	// io.num1.storeFix(fix1)
	// val fix2 = Wire(new FixedPoint(s,d,f))
	// io.num2.storeFix(fix2)
	// val sum = fix1 + fix2
	// sum.storeRaw(io.add_result)
	// val prod = fix1 * fix2
	// prod.storeRaw(io.prod_result)
	// val sub = fix1 - fix2
	// sub.storeRaw(io.sub_result)
	// val quotient = fix1 / fix2
	// quotient.storeRaw(io.quotient_result)

	val fix1 = io.num1.asFixedPoint(BinaryPoint(f))
	val fix2 = io.num2.asFixedPoint(BinaryPoint(f))
	io.add_result := (fix1 + fix2).toUInt()
	io.prod_result := (fix1 * fix2).toUInt()




}



// import Node._
// import ChiselError._

// /** Factory methods for [[Chisel.Fixed Fixed]] */
// object Fixed {
//     /** Convert a double to fixed point with a specified fractional width
//       * @param x Double to convert
//       * @param fracWidth the integer fractional width to use in the conversion
//       * @return A BigInt representing the bits in the fixed point
//       */
//     def toFixed(x : Double, fracWidth : Int) : BigInt = BigInt(scala.math.round(x*scala.math.pow(2, fracWidth)))
//     /** Convert a Float to fixed point with a specified fractional width
//       * @param x Float to convert
//       * @param fracWidth the integer fractional width to use in the conversion
//       * @return A BigInt representing the bits in the fixed point
//       */
//     def toFixed(x : Float, fracWidth : Int) : BigInt = BigInt(scala.math.round(x*scala.math.pow(2, fracWidth)))
//     /** Convert an Int to fixed point with a specified fractional width
//       * @param x Double to convert
//       * @param fracWidth the integer fractional width to use in the conversion
//       * @return A BigInt representing the bits in the fixed point
//       */
//     def toFixed(x : Int, fracWidth : Int) : BigInt = BigInt(scala.math.round(x*scala.math.pow(2, fracWidth)))

//     /** Create a Fixed [[Chisel.Node]] with specified width and fracWidth
//       * @param x An Int to convert to fixed point
//       * @param width the total number of bits to use in the representation
//       * @param fracWidth the integer fractional width to use in the conversion
//       * @return A fixed node with the specified parameters
//       */
//     def apply(x : Int, width : Int, fracWidth : Int) : Fixed = apply(toFixed(x, fracWidth), width, fracWidth)
//     /** Create a Fixed [[Chisel.Node]] with specified width and fracWidth
//       * @param x An Float to convert to fixed point
//       * @param width the total number of bits to use in the representation
//       * @param fracWidth the integer fractional width to use in the conversion
//       * @return A fixed node with the specified parameters
//       */
//     def apply(x : Float, width : Int, fracWidth : Int) : Fixed = apply(toFixed(x, fracWidth), width, fracWidth)
//     /** Create a Fixed [[Chisel.Node]] with specified width and fracWidth
//       * @param x An Double to convert to fixed point
//       * @param width the total number of bits to use in the representation
//       * @param fracWidth the integer fractional width to use in the conversion
//       * @return A fixed node with the specified parameters
//       */
//     def apply(x : Double, width : Int, fracWidth : Int) : Fixed = apply(toFixed(x, fracWidth), width, fracWidth)
//     /** Create a Fixed [[Chisel.Node]] with specified width and fracWidth
//       * @param x An BigInt to use literally as the fixed point bits
//       * @param width the total number of bits to use in the representation
//       * @param fracWidth the integer fractional width to use
//       * @return A fixed node with the specified parameters
//       */
//     def apply(x : BigInt, width : Int, fracWidth : Int) : Fixed =  {
//       val res = Lit(x, width){Fixed()}
//       res.fractionalWidth = fracWidth
//       res
//     }

//     /** Create a Fixed I/O [[Chisel.Node]] with specified width and fracWidth
//       * @param dir Direction of I/O for the node, eg) INPUT or OUTPUT
//       * @param width the total number of bits to use in the representation
//       * @param fracWidth the integer fractional width to use
//       * @return A fixed node with the specified parameters
//       */
//     def apply(dir : IODirection = null, width : Int = -1, fracWidth : Int = -1) : Fixed = {
//         val res = new Fixed(fracWidth);
//         res.create(dir, width)
//         res
//     }
// }

// /** A Fixed point data type
//   * @constructor Use [[Chisel.Fixed$ Fixed]] object to create rather than this class directly */
// class Fixed(var fractionalWidth : Int = 0) extends Bits with Num[Fixed] {
//     type T = Fixed

//     /** Convert a Node to a Fixed data type with the same fractional width as this instantiation */
//     override def fromNode(n : Node): this.type = {
//         val res = Fixed(OUTPUT).asTypeFor(n).asInstanceOf[this.type]
//         res.fractionalWidth = this.getFractionalWidth()
//         res
//     }

//     /** Create a Fixed representation from an Int */
//     override def fromInt(x : Int) : this.type = Fixed(x, this.getWidth(), this.getFractionalWidth()).asInstanceOf[this.type]

//     /** clone this Fixed instantiation */
//     override def cloneType: this.type = Fixed(this.dir, this.getWidth(), this.getFractionalWidth()).asInstanceOf[this.type];

//     override protected def colonEquals(that : Bits): Unit = that match {
//       case f: Fixed => {
//         val res = if((f.getWidth() == this.getWidth()*2) && (f.getFractionalWidth() == this.getFractionalWidth()*2)) {
//           truncate(f, this.getFractionalWidth())
//         } else {
//           checkAligned(f)
//           f
//         }
//         super.colonEquals(res)
//       }
//       case _ => illegalAssignment(that)
//     }

//     def getFractionalWidth() : Int = this.fractionalWidth

//     private def truncate(f : Fixed, truncateAmount : Int) : Fixed = fromSInt(f.toSInt >> UInt(truncateAmount))
//     private def truncate(f : SInt, truncateAmount : Int) : SInt = f >> UInt(truncateAmount)

//     /** Ensure two Fixed point data types have the same fractional width, Error if not */
//     private def checkAligned(b : Fixed) {
//       if(this.getFractionalWidth() != b.getFractionalWidth()) ChiselError.error(this.getFractionalWidth() + " Fractional Bits does not match " + b.getFractionalWidth())
//       if(this.getWidth() != b.getWidth()) ChiselError.error(this.getWidth() + " Width does not match " + b.getWidth())
//     }

//     /** Convert a SInt to a Fixed by reinterpreting the Bits */
//     private def fromSInt(s : SInt, width : Int = this.getWidth(), fracWidth : Int = this.getFractionalWidth()) : Fixed = {
//         val res = chiselCast(s){Fixed()}
//         res.fractionalWidth = fracWidth
//         res.width = width
//         res
//     }

//     // Order Operators
//     def > (b : Fixed) : Bool = {
//         checkAligned(b)
//         this.toSInt > b.toSInt
//     }

//     def < (b : Fixed) : Bool = {
//         checkAligned(b)
//         this.toSInt < b.toSInt
//     }

//     def >= (b : Fixed) : Bool = {
//         checkAligned(b)
//         this.toSInt >= b.toSInt
//     }

//     def <= (b : Fixed) : Bool = {
//         checkAligned(b)
//         this.toSInt <= b.toSInt
//     }

//     def === (b : Fixed) : Bool = {
//         checkAligned(b)
//         this.toSInt === b.toSInt
//     }

//     def >> (b : UInt) : Fixed = {
//         fromSInt(this.toSInt >> b)
//     }

//     // Arithmetic Operators
//     def unary_-() : Fixed = Fixed(0, this.getWidth(), this.getFractionalWidth()) - this

//     def + (b : Fixed) : Fixed = {
//         checkAligned(b)
//         fromSInt(this.toSInt + b.toSInt)
//     }

//     def - (b : Fixed) : Fixed = {
//         checkAligned(b)
//         fromSInt(this.toSInt - b.toSInt)
//     }

//     /** Multiply increasing the Bit Width */
//     def * (b : Fixed) : Fixed = {
//         checkAligned(b)
//         val temp = this.toSInt * b.toSInt
//         fromSInt(temp, temp.getWidth(), this.getFractionalWidth()*2)
//     }

//     /** Multiply with one bit of rounding */
//     def *& (b : Fixed) : Fixed = {
//         checkAligned(b)
//         val temp = this.toSInt * b.toSInt
//         val res = temp + ((temp & UInt(1)<<UInt(this.getFractionalWidth()-1))<<UInt(1))
//         fromSInt(truncate(res, this.getFractionalWidth()))
//     }

//     /** Multiply truncating the result to the same Fixed format */
//     def *% (b : Fixed) : Fixed = {
//         checkAligned(b)
//         val temp = this.toSInt * b.toSInt
//         fromSInt(truncate(temp, this.getFractionalWidth()))
//     }

//     def / (b : Fixed) : Fixed = {
//         checkAligned(b)
//         fromSInt((this.toSInt << UInt(this.getFractionalWidth())) / b.toSInt)
//     }

//     /** This is just the modulo of the two fixed point bit representations changed into SInt and operated on */
//     def % (b : Fixed) : Fixed = {
//       checkAligned(b)
//       fromSInt(this.toSInt % b.toSInt)
//     }
// }
