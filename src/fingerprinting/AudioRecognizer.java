package fingerprinting;

import serialization.Serialization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import utilities.HashingFunctions;
import utilities.Spectrum;

public class AudioRecognizer {
    
    // The main hashtable required in our interpretation of the algorithm to
    // store the song repository
    private Map<Long, List<KeyPoint>> hashMapSongRepository;

    // Variable to stop/start the listening loop
    public boolean running;

    // Constructor
    public AudioRecognizer() {
        
        // Deserialize the hash table hashMapSongRepository (our song repository)
        this.hashMapSongRepository = Serialization.deserializeHashMap();
        this.running = true;
    }

    // Method used to acquire audio from the microphone and to add/match a song fragment
    public void listening(String songId, boolean isMatching) throws LineUnavailableException {
        
        // Fill AudioFormat with the recording we want for settings
        AudioFormat audioFormat = new AudioFormat(AudioParams.sampleRate,
                AudioParams.sampleSizeInBits, AudioParams.channels,
                AudioParams.signed, AudioParams.bigEndian);
        
        // Required to get audio directly from the microphone and process it as an 
        // InputStream (using TargetDataLine) in another thread      
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
        final TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(audioFormat);
        line.start();
        
        Thread listeningThread = new Thread(new Runnable() {
                        
            @Override
            public void run() {
                // Output stream 
                ByteArrayOutputStream outStream = new ByteArrayOutputStream();
                // Reader buffer
                byte[] buffer = new byte[AudioParams.bufferSize];               
                int n = 0;
                try {
                    while (running) {
                        // Reading
                        int count = line.read(buffer, 0, buffer.length);
                        // If buffer is not empty
                        if (count > 0) {
                            outStream.write(buffer, 0, count);
                        }
                    }

                    byte[] audioTimeDomain = outStream.toByteArray();

                    // Compute magnitude spectrum
                    double [][] magnitudeSpectrum = Spectrum.compute(audioTimeDomain);                    
                    // Determine the shazam action (add or matching) and perform it
                    shazamAction(magnitudeSpectrum, songId, isMatching);                    
                    // Close stream
                    outStream.close();                    
                    // Serialize again the hashMapSongRepository (our song repository)
                    Serialization.serializeHashMap(hashMapSongRepository);                
                } catch (IOException e) {
                    System.err.println("I/O exception " + e);
                    System.exit(-1);
                }
            }
        });

        // Start listening
        listeningThread.start();
        
        System.out.println("Press ENTER key to stop listening...");
        try {
            System.in.read();
        } catch (IOException ex) {
            Logger.getLogger(AudioRecognizer.class.getName()).log(Level.SEVERE, null, ex);
        }
        this.running = false;               
    }   
    
    // Determine the shazam action (add or matching a song) and perform it 
    private void shazamAction(double[][] magnitudeSpectrum, String songId, boolean isMatching) {  
        
        // Hash table used for matching (Map<songId, Map<offset,count>>)
        Map<String, Map<Integer,Integer>> matchMap = new HashMap<String, Map<Integer,Integer>>(); 
    
        // Iterate over all the chunks/ventanas from the magnitude spectrum
        for (int c = 0; c < magnitudeSpectrum.length; c++) {
        	
            // Compute the hash entry for the current chunk/ventana (magnitudeSpectrum[c])        	
        	long hashentry = computeHashEntry(magnitudeSpectrum[c]);
        	
            // In the case of adding the song to the repository
            if (!isMatching) {
            	
                // Adding keypoint to the list in its relative hash entry which has been computed before            	
            	KeyPoint point = new KeyPoint(songId, c);
            	
            	if (!this.hashMapSongRepository.containsKey(hashentry)) {            		
            		List<KeyPoint> listofkeys = new ArrayList<KeyPoint>();
            		listofkeys.add(point);                
                	this.hashMapSongRepository.put(hashentry, listofkeys);
                }else {
                	List<KeyPoint> songlist = this.hashMapSongRepository.get(hashentry);
                	songlist.add(point);
                	//this.hashMapSongRepository.put(hashentry, songlist); // Actualizar lista de keypoints
                }
                
            }
            // In the case of matching a song fragment
            else {
                    // Iterate over the list of keypoints that matches the hash entry
                    // in the the current chunk
                        // For each keypoint:
                            // Compute the time offset (Math.abs(point.getTimestamp() - c))
                            
    					List<KeyPoint> listn = this.hashMapSongRepository.get(hashentry);
    					if(listn != null) {
            				for(KeyPoint kp : listn) {
            					int time = kp.getTimestamp();
            					String id = kp.getSongId();
            					int offset = Math.abs(time - c);
            					
            					// La cancion no existe en el matchMap
            					if(!matchMap.containsKey(id)) {
            						Map<Integer, Integer> smap = new HashMap<Integer, Integer>();
            						smap.put(offset, 1);
            						matchMap.put(id, smap); // Creando la cancion el matchMap
            						
            					// La canción está en el matchMap
            					}else{
            						Map<Integer, Integer> offsetmap = matchMap.get(id);
            						// No está el offset actual
            						if(!offsetmap.containsKey(offset)) {
            							// Crear la entrada para ese offset
            							offsetmap.put(offset, 1);           							
        							// El offset actual está
            						}else{
            							// Incrementar el contador
            							Integer cont = offsetmap.get(offset);
            							offsetmap.put(offset, cont + 1);
            						}
            					}
            				
            				}
    					}	
            }            
        } // End iterating over the chunks/ventanas of the magnitude spectrum
        // If we chose matching, we 
        if (isMatching) {
           showBestMatching(matchMap);
        }
    }
    
    // Find out in which range the frequency is
    private int getIndex(int freq) {
       
        int i = 0;
        while (AudioParams.range[i] < freq) {
            i++;
        }
        return i;
    }  
    
    // Compute hash entry for the chunk/ventana spectra 
    private long computeHashEntry(double[] chunk) {
                
        // Variables to determine the hash entry for this chunk/window spectra
        double highscores[] = new double[AudioParams.range.length];
        int frequencyPoints[] = new int[AudioParams.range.length];
       
        for (int freq = AudioParams.lowerLimit; freq < AudioParams.unpperLimit - 1; freq++) {
            // Get the magnitude
            double mag = chunk[freq];
            // Find out which range we are in
            int index = getIndex(freq);
            // Save the highest magnitude and corresponding frequency:
            if (mag > highscores[index]) {
                highscores[index] = mag;
                frequencyPoints[index] = freq;
            }
        }        
        // Hash function 
        return HashingFunctions.hash1(frequencyPoints[0], frequencyPoints[1], 
                frequencyPoints[2],frequencyPoints[3],AudioParams.fuzzFactor);
    }
    
    // Method to find the songId with the most frequently/repeated time offset
    private void showBestMatching(Map<String, Map<Integer,Integer>> matchMap) {
    	
    	String bestsong = "";
    	int bestmatch = 0;
    	
    	// Iterar el Hashmap anidado para comparar los offset.
    	for (Map.Entry<String, Map<Integer, Integer>> entry : matchMap.entrySet()) { // Cada canción
    		String idsong = entry.getKey(); // Tomamos el ID de la canción
    		Map<Integer, Integer> offsetmap = entry.getValue();
    		int biggestoffset_of_a_song = 0;
    	    for(Map.Entry<Integer, Integer> entry2 : offsetmap.entrySet()) { // Recorre map de offsets
    	    	int current_cont = entry2.getValue();
    	    	// Comprobar cuál es el offset más grande de cada canción
    	    	if(current_cont > biggestoffset_of_a_song) {
    	    		biggestoffset_of_a_song = current_cont;
    	    	}
    	    	
    	    }
    	    
    	    // Comprobar que canción tiene mejor matching
    	    if (biggestoffset_of_a_song > bestmatch) {
    	    	bestmatch = biggestoffset_of_a_song;
    	    	bestsong = idsong;
    	    }
    	}
               
        // Print the songId string which represents the best matching     
        System.out.println("Best song: " + bestsong);
    }
}