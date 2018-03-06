import java.util.Random;

/*
 * PLD模块
 * */

public class PLD {
	int seed;
	float pdrop;
	
	public PLD(int seed,float pdrop){
		this.seed = seed;
		this.pdrop = pdrop;
	}
	
	public boolean drop(){
		Random random = new Random();
		
		if(random.nextFloat()>this.pdrop){
			return false;
		}else {
			return true;
		}
		
	}

}
