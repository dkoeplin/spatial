package spatial.targets

object DE1 extends FPGATarget {
  val name = "DE1"
  def burstSize = 96 

  case object VideoCamera extends Bus {def length = 16}
  case object VGA extends Bus {def length = 16}
  case object SliderSwitch extends Bus {def length = 10}
  case object LEDR extends Bus {
    def length = 10; 
    val LEDR0 = 1;
    val LEDR1 = 2;
    val LEDR2 = 4;
    val LEDR3 = 8;
    val LEDR4 = 16;
    val LEDR5 = 32;
    val LEDR6 = 64;
    val LEDR7 = 128;
    val LEDR8 = 256;
    val LEDR9 = 512;
  }

  case object GPInput extends Bus { def length = 32 }
  case object GPOutput extends Bus { def length = 32 }
  case object GPIO extends Bus { def length = 32 }
}
