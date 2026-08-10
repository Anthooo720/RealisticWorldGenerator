package fr.antho.realisticworld.hydrology;

import fr.antho.realisticworld.config.WorldGenConfig;
import fr.antho.realisticworld.noise.SimplexNoise;
import fr.antho.realisticworld.terrain.TerrainEngine;
import fr.antho.realisticworld.util.BoundedCache;
import fr.antho.realisticworld.util.HashUtil;
import fr.antho.realisticworld.util.MathUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Bassins versants : Priority-Flood, drainage D8, accumulation et segments de chenal
 * continus. La cote hydraulique reste en double jusqu'à WaterColumnEngine.
 *
 * <p>L'index spatial est construit en projetant chaque segment seulement sur sa zone
 * d'influence. L'ancienne version balayait une fenêtre complète autour de chaque nœud,
 * ce qui multipliait les projections Catmull-Rom lors du premier accès à une tuile.</p>
 */
public final class WatershedEngine {
    private final long seed;
    private final TerrainEngine terrain;
    private final WorldGenConfig.Rivers cfg;
    private final BoundedCache<TileKey,Tile> cache;
    private final SimplexNoise channelWarp;
    private final SimplexNoise channelWarpDetail;

    public WatershedEngine(long seed,TerrainEngine terrain,WorldGenConfig.Rivers cfg,int cacheTiles) {
        this.seed=seed;
        this.terrain=terrain;
        this.cfg=cfg;
        this.cache=new BoundedCache<>(Math.max(8,cacheTiles));
        this.channelWarp=new SimplexNoise(seed ^ 0x5741544552574152L);
        this.channelWarpDetail=new SimplexNoise(seed ^ 0x5741544552444554L);
    }

    public HydroSample sample(double x,double z) {
        int ts=Math.max(256,cfg.tileSize());
        int tx=Math.floorDiv((int)Math.floor(x),ts);
        int tz=Math.floorDiv((int)Math.floor(z),ts);
        return cache.computeIfAbsent(new TileKey(tx,tz),this::buildTile).sample(x,z);
    }

    private Tile buildTile(TileKey key) {
        int spacing=Math.max(4,cfg.sampleSpacing());
        int core=Math.max(24,cfg.tileSize()/spacing);
        int margin=Math.max(10,cfg.marginSamples());
        int n=core+margin*2+1;
        int ox=key.x*cfg.tileSize()-margin*spacing;
        int oz=key.z*cfg.tileSize()-margin*spacing;
        int size=n*n;

        double[] h=new double[size];
        double[] filled=new double[size];
        double[] flow=new double[size];
        int[] downstream=new int[size];
        int[] root=new int[size];
        long[] basin=new long[size];
        Arrays.fill(downstream,-1);
        Arrays.fill(root,-1);
        Integer[] order=new Integer[size];

        sampleTerrainGrid(ox,oz,spacing,n,h,filled,flow,order);
        priorityFlood(n,h,filled);
        buildD8(n,h,filled,downstream);

        Arrays.sort(order,(a,b)->Double.compare(filled[b],filled[a]));
        accumulateFlow(order,downstream,flow);
        computeBasins(ox,oz,spacing,n,downstream,root,basin);

        double[] channelX=new double[size];
        double[] channelZ=new double[size];
        warpChannels(ox,oz,spacing,n,h,flow,downstream,channelX,channelZ);

        boolean[] center=new boolean[size];
        double[] safeWater=new double[size];
        Arrays.fill(safeWater,Double.POSITIVE_INFINITY);
        buildSafeWater(n,h,filled,flow,downstream,channelX,channelZ,center,safeWater);
        enforceDownstreamMonotonicity(order,downstream,center,safeWater);

        int[] mainUpstream=mainUpstream(center,downstream,flow);
        Segment[] segments=buildSegments(n,h,flow,downstream,channelX,channelZ,center,safeWater,mainUpstream);
        int[] nearestSegment=indexSegments(ox,oz,spacing,n,segments);

        return new Tile(ox,oz,spacing,n,h,filled,flow,basin,nearestSegment,segments);
    }

    private void sampleTerrainGrid(int ox,int oz,int spacing,int n,double[] h,double[] filled,
                                   double[] flow,Integer[] order) {
        int sea=terrain.seaLevel();
        for(int z=0;z<n;z++) for(int x=0;x<n;x++) {
            int i=z*n+x;
            double elevation=terrain.heightWithoutRivers(ox+x*spacing,oz+z*spacing);
            h[i]=filled[i]=elevation;
            flow[i]=0.82+MathUtil.clamp((elevation-sea)/180.0,0,1)*0.34;
            order[i]=i;
        }
    }

    private static void priorityFlood(int n,double[] h,double[] filled) {
        int size=n*n;
        boolean[] visited=new boolean[size];
        PriorityQueue<FloodNode> queue=new PriorityQueue<>(Comparator.comparingDouble(FloodNode::height));
        for(int x=0;x<n;x++) {
            seedBoundary(x,0,n,filled,visited,queue);
            seedBoundary(x,n-1,n,filled,visited,queue);
        }
        for(int z=1;z<n-1;z++) {
            seedBoundary(0,z,n,filled,visited,queue);
            seedBoundary(n-1,z,n,filled,visited,queue);
        }

        int[] dx={-1,0,1,-1,1,-1,0,1};
        int[] dz={-1,-1,-1,0,0,1,1,1};
        final double epsilon=0.002;
        while(!queue.isEmpty()) {
            FloodNode node=queue.poll();
            int i=node.index;
            int x=i%n,z=i/n;
            for(int k=0;k<8;k++) {
                int nx=x+dx[k],nz=z+dz[k];
                if(nx<0||nx>=n||nz<0||nz>=n) continue;
                int ni=nz*n+nx;
                if(visited[ni]) continue;
                visited[ni]=true;
                filled[ni]=Math.max(h[ni],node.height+epsilon);
                queue.add(new FloodNode(ni,filled[ni]));
            }
        }
    }

    private static void buildD8(int n,double[] h,double[] filled,int[] downstream) {
        int[] dx={-1,0,1,-1,1,-1,0,1};
        int[] dz={-1,-1,-1,0,0,1,1,1};
        for(int z=1;z<n-1;z++) for(int x=1;x<n-1;x++) {
            int i=z*n+x;
            double bestFilled=filled[i];
            double bestOriginal=Double.POSITIVE_INFINITY;
            int best=-1;
            for(int k=0;k<8;k++) {
                int ni=(z+dz[k])*n+(x+dx[k]);
                double f=filled[ni];
                if(f<bestFilled-1.0e-8 || (Math.abs(f-bestFilled)<1.0e-8 && h[ni]<bestOriginal)) {
                    bestFilled=f;
                    bestOriginal=h[ni];
                    best=ni;
                }
            }
            downstream[i]=best;
        }
    }

    private static void accumulateFlow(Integer[] order,int[] downstream,double[] flow) {
        for(int i:order) {
            int d=downstream[i];
            if(d>=0) flow[d]+=flow[i];
        }
    }

    private void computeBasins(int ox,int oz,int spacing,int n,int[] downstream,int[] root,long[] basin) {
        for(int i=0;i<downstream.length;i++) {
            int r=findRoot(i,downstream,root);
            int rx=r%n,rz=r/n;
            basin[i]=HashUtil.hash(seed,ox+rx*spacing,oz+rz*spacing,0x424153494E4944L);
        }
    }

    private void warpChannels(int ox,int oz,int spacing,int n,double[] h,double[] flow,int[] downstream,
                              double[] channelX,double[] channelZ) {
        double qNormDen=Math.log1p(Math.max(8.0,cfg.accumulationThreshold()*22.0));
        double warpScale=Math.max(0.00020,cfg.meanderScale());
        for(int z=0;z<n;z++) for(int x=0;x<n;x++) {
            int i=z*n+x;
            double gx=ox+x*spacing,gz=oz+z*spacing;
            channelX[i]=gx;
            channelZ[i]=gz;
            int d=downstream[i];
            if(d<0) continue;

            double dxw=(d%n-x)*spacing,dzw=(d/n-z)*spacing;
            double len=Math.max(1.0e-6,Math.hypot(dxw,dzw));
            double nx=-dzw/len,nz=dxw/len;
            double normalized=MathUtil.clamp(Math.log1p(Math.max(1.0,flow[i]))/qNormDen,0,1);
            double grade=Math.abs(h[i]-h[d])/len;
            double calm=1.0-MathUtil.smoothstep(0.035,0.18,grade);
            double broad=channelWarp.sample(gx*warpScale,gz*warpScale);
            double detail=channelWarpDetail.sample(gx*warpScale*2.1+29,gz*warpScale*2.1-47);
            double amplitude=(0.20+7.2*Math.pow(normalized,1.24))*cfg.meanderStrength()*(0.18+0.82*calm);
            amplitude=MathUtil.clamp(amplitude,0.10,9.5);
            double offset=(broad*0.84+detail*0.16)*amplitude;
            channelX[i]=gx+nx*offset;
            channelZ[i]=gz+nz*offset;
        }
    }

    private void buildSafeWater(int n,double[] h,double[] filled,double[] flow,int[] downstream,
                                double[] channelX,double[] channelZ,boolean[] center,double[] safeWater) {
        double threshold=Math.max(4.0,cfg.accumulationThreshold());
        for(int z=1;z<n-1;z++) for(int x=1;x<n-1;x++) {
            int i=z*n+x;
            if(h[i]<=terrain.seaLevel()-1 || flow[i]<threshold) continue;
            if(filled[i]-h[i]>1.60) continue;
            int d=downstream[i];
            if(d<0) continue;
            center[i]=true;

            double x1=channelX[i],z1=channelZ[i];
            double x2=channelX[d],z2=channelZ[d];
            double sx=x2-x1,sz=z2-z1;
            double len=Math.max(1.0e-6,Math.hypot(sx,sz));
            double nx=-sz/len,nz=sx/len;
            double width=widthFor(flow[i]);
            double probe=Math.max(2.5,width*0.5+cfg.bankSlopeWidth()*1.25+1.0);
            double bankA=terrain.heightWithoutRivers(x1+nx*probe,z1+nz*probe);
            double bankB=terrain.heightWithoutRivers(x1-nx*probe,z1-nz*probe);
            double localBank=Math.min(bankA,bankB);
            double level=Math.min(h[i],localBank)-cfg.bankBuffer();
            if(h[i]<=terrain.seaLevel()+cfg.coastalMergeHeight()) {
                level=Math.min(level,coastalWaterCeiling(h[i]));
            }
            safeWater[i]=level;
        }
    }

    private static void enforceDownstreamMonotonicity(Integer[] order,int[] downstream,
                                                        boolean[] center,double[] safeWater) {
        for(int i:order) {
            if(!center[i]||!Double.isFinite(safeWater[i])) continue;
            int d=downstream[i];
            if(d>=0 && center[d] && Double.isFinite(safeWater[d])) {
                safeWater[d]=Math.min(safeWater[d],safeWater[i]);
            }
        }
    }

    private static int[] mainUpstream(boolean[] center,int[] downstream,double[] flow) {
        int[] main=new int[center.length];
        Arrays.fill(main,-1);
        for(int i=0;i<center.length;i++) {
            if(!center[i]) continue;
            int d=downstream[i];
            if(d<0||!center[d]) continue;
            int previous=main[d];
            if(previous<0||flow[i]>flow[previous]) main[d]=i;
        }
        return main;
    }

    private Segment[] buildSegments(int n,double[] h,double[] flow,int[] downstream,
                                    double[] channelX,double[] channelZ,boolean[] center,
                                    double[] safeWater,int[] mainUpstream) {
        List<Segment> segments=new ArrayList<>();
        for(int i=0;i<center.length;i++) {
            if(!center[i]||!Double.isFinite(safeWater[i])) continue;
            int d=downstream[i];
            if(d<0) continue;

            double x1=channelX[i],z1=channelZ[i];
            double x2=channelX[d],z2=channelZ[d];
            int up=mainUpstream[i];
            int dd=downstream[d];
            double x0=up>=0?channelX[up]:x1-(x2-x1);
            double z0=up>=0?channelZ[up]:z1-(z2-z1);
            double x3=dd>=0?channelX[dd]:x2+(x2-x1);
            double z3=dd>=0?channelZ[dd]:z2+(z2-z1);
            double w1=safeWater[i];
            double w2;
            if(center[d]&&Double.isFinite(safeWater[d])) {
                w2=Math.min(w1,safeWater[d]);
            } else if(h[d]<=terrain.seaLevel()+cfg.coastalMergeHeight()) {
                w2=Math.min(w1,coastalWaterCeiling(h[d]));
            } else {
                w2=Math.min(w1,h[d]-cfg.bankBuffer());
            }
            segments.add(Segment.create(x0,z0,x1,z1,x2,z2,x3,z3,h[i],h[d],flow[i],flow[d],w1,w2));
        }
        return segments.toArray(Segment[]::new);
    }

    private int[] indexSegments(int ox,int oz,int spacing,int n,Segment[] segments) {
        int[] nearest=new int[n*n];
        Arrays.fill(nearest,-1);
        double[] distance=new double[n*n];
        Arrays.fill(distance,Double.POSITIVE_INFINITY);

        // Rayon maximal de tout ce que RiverEngine peut utiliser autour d'un chenal.
        double influence=cfg.maxWidth()*0.62
                +cfg.bankSlopeWidth()*1.85
                +cfg.floodplainWidth()*1.62+8.0;

        for(int sid=0;sid<segments.length;sid++) {
            Segment s=segments[sid];
            int gx0=MathUtil.clamp((int)Math.floor((s.minX()-influence-ox)/spacing),0,n-1);
            int gx1=MathUtil.clamp((int)Math.ceil((s.maxX()+influence-ox)/spacing),0,n-1);
            int gz0=MathUtil.clamp((int)Math.floor((s.minZ()-influence-oz)/spacing),0,n-1);
            int gz1=MathUtil.clamp((int)Math.ceil((s.maxZ()+influence-oz)/spacing),0,n-1);

            for(int gz=gz0;gz<=gz1;gz++) {
                double pz=oz+gz*spacing;
                for(int gx=gx0;gx<=gx1;gx++) {
                    double px=ox+gx*spacing;
                    double d=s.distance(px,pz);
                    if(d>influence) continue;
                    int i=gz*n+gx;
                    if(d<distance[i]) {
                        distance[i]=d;
                        nearest[i]=sid;
                    }
                }
            }
        }
        return nearest;
    }

    private double widthFor(double q) {
        double normalized=MathUtil.clamp(
                Math.log1p(Math.max(1.0,q))/Math.log1p(Math.max(8.0,cfg.accumulationThreshold()*22.0)),0,1);
        double width=cfg.minWidth()+(cfg.maxWidth()-cfg.minWidth())*Math.pow(normalized,1.22);
        return MathUtil.clamp(width,cfg.minWidth(),cfg.maxWidth());
    }

    private double coastalWaterCeiling(double terrainHeight) {
        double sea=terrain.seaLevel();
        double above=Math.max(0.0,terrainHeight-(sea+1.5));
        double rise=above*MathUtil.clamp(cfg.coastalWaterGradient(),0.05,0.80);
        return sea+Math.min(rise,Math.max(1.0,cfg.coastalMergeHeight()*0.42));
    }

    private static int findRoot(int start,int[] downstream,int[] root) {
        if(root[start]>=0) return root[start];
        int p=start,guard=0;
        while(downstream[p]>=0&&downstream[p]!=p&&root[p]<0&&guard++<downstream.length) p=downstream[p];
        int r=root[p]>=0?root[p]:p;
        p=start;
        guard=0;
        while(p!=r&&root[p]<0&&guard++<downstream.length) {
            int next=downstream[p];
            root[p]=r;
            if(next<0||next==p) break;
            p=next;
        }
        root[start]=r;
        return r;
    }

    private static void seedBoundary(int x,int z,int n,double[] filled,boolean[] visited,
                                     PriorityQueue<FloodNode> queue) {
        int i=z*n+x;
        if(visited[i]) return;
        visited[i]=true;
        queue.add(new FloodNode(i,filled[i]));
    }

    public record HydroSample(double elevation,double filledElevation,double depressionDepth,
                              double accumulation,long basinId,double channelDistance,
                              double channelAccumulation,double channelElevation,
                              double channelPotential,double channelWaterSurface,
                              double channelGrade,double channelDirX,double channelDirZ,
                              double channelSignedDistance) {}

    private record TileKey(int x,int z) {}
    private record FloodNode(int index,double height) {}
    private record Point(double x,double z) {}
    private record Projection(double distance,double t,double signedDistance) {}

    private record Segment(double x0,double z0,double x1,double z1,double x2,double z2,
                           double x3,double z3,double e1,double e2,double q1,double q2,
                           double w1,double w2,double length) {
        private static final int CURVE_STEPS=5;

        static Segment create(double x0,double z0,double x1,double z1,double x2,double z2,
                              double x3,double z3,double e1,double e2,double q1,double q2,
                              double w1,double w2) {
            double length=curveLength(x0,z0,x1,z1,x2,z2,x3,z3);
            return new Segment(x0,z0,x1,z1,x2,z2,x3,z3,e1,e2,q1,q2,w1,w2,length);
        }

        Projection project(double x,double z) {
            double bestD=Double.POSITIVE_INFINITY,bestT=0,bestSigned=0;
            double ax=x1,az=z1;
            for(int step=1;step<=CURVE_STEPS;step++) {
                double t1=step/(double)CURVE_STEPS;
                Point b=point(t1);
                double vx=b.x-ax,vz=b.z-az;
                double len2=vx*vx+vz*vz;
                double local=len2<=1.0e-12?0.0:((x-ax)*vx+(z-az)*vz)/len2;
                local=MathUtil.clamp(local,0,1);
                double px=ax+vx*local,pz=az+vz*local;
                double dx=x-px,dz=z-pz;
                double dist=Math.hypot(dx,dz);
                if(dist<bestD) {
                    bestD=dist;
                    double t0=(step-1)/(double)CURVE_STEPS;
                    bestT=MathUtil.lerp(t0,t1,local);
                    double len=Math.max(1.0e-9,Math.hypot(vx,vz));
                    bestSigned=(vx*dz-vz*dx)/len;
                }
                ax=b.x;
                az=b.z;
            }
            return new Projection(bestD,bestT,bestSigned);
        }

        double distance(double x,double z) {
            double best=Double.POSITIVE_INFINITY;
            double ax=x1,az=z1;
            for(int step=1;step<=CURVE_STEPS;step++) {
                double t=step/(double)CURVE_STEPS;
                Point b=point(t);
                double vx=b.x-ax,vz=b.z-az;
                double len2=vx*vx+vz*vz;
                double local=len2<=1.0e-12?0.0:((x-ax)*vx+(z-az)*vz)/len2;
                local=MathUtil.clamp(local,0,1);
                double px=ax+vx*local,pz=az+vz*local;
                best=Math.min(best,Math.hypot(x-px,z-pz));
                ax=b.x;
                az=b.z;
            }
            return best;
        }

        Point point(double t) {
            return curvePoint(x0,z0,x1,z1,x2,z2,x3,z3,t);
        }

        Point tangent(double t) {
            double t2=t*t;
            double tx=0.5*((-x0+x2)+2*(2*x0-5*x1+4*x2-x3)*t+3*(-x0+3*x1-3*x2+x3)*t2);
            double tz=0.5*((-z0+z2)+2*(2*z0-5*z1+4*z2-z3)*t+3*(-z0+3*z1-3*z2+z3)*t2);
            return new Point(tx,tz);
        }

        double minX(){ return Math.min(Math.min(x0,x1),Math.min(x2,x3)); }
        double maxX(){ return Math.max(Math.max(x0,x1),Math.max(x2,x3)); }
        double minZ(){ return Math.min(Math.min(z0,z1),Math.min(z2,z3)); }
        double maxZ(){ return Math.max(Math.max(z0,z1),Math.max(z2,z3)); }

        private static double curveLength(double x0,double z0,double x1,double z1,double x2,double z2,
                                          double x3,double z3) {
            double length=0,ax=x1,az=z1;
            for(int step=1;step<=CURVE_STEPS;step++) {
                Point p=curvePoint(x0,z0,x1,z1,x2,z2,x3,z3,step/(double)CURVE_STEPS);
                length+=Math.hypot(p.x-ax,p.z-az);
                ax=p.x;
                az=p.z;
            }
            return Math.max(1.0e-6,length);
        }

        private static Point curvePoint(double x0,double z0,double x1,double z1,double x2,double z2,
                                        double x3,double z3,double t) {
            double t2=t*t,t3=t2*t;
            double px=0.5*((2*x1)+(-x0+x2)*t+(2*x0-5*x1+4*x2-x3)*t2+(-x0+3*x1-3*x2+x3)*t3);
            double pz=0.5*((2*z1)+(-z0+z2)*t+(2*z0-5*z1+4*z2-z3)*t2+(-z0+3*z1-3*z2+z3)*t3);
            return new Point(px,pz);
        }
    }

    private record Tile(int ox,int oz,int spacing,int n,double[] h,double[] filled,double[] flow,
                        long[] basin,int[] nearestSegment,Segment[] segments) {
        HydroSample sample(double x,double z) {
            double gx=(x-ox)/spacing,gz=(z-oz)/spacing;
            int ix=MathUtil.clamp((int)Math.floor(gx),0,n-2);
            int iz=MathUtil.clamp((int)Math.floor(gz),0,n-2);
            double tx=MathUtil.clamp(gx-ix,0,1),tz=MathUtil.clamp(gz-iz,0,1);
            int a=iz*n+ix,b=a+1,c=(iz+1)*n+ix,d=c+1;
            double eh=MathUtil.bilerp(h[a],h[b],h[c],h[d],tx,tz);
            double fh=MathUtil.bilerp(filled[a],filled[b],filled[c],filled[d],tx,tz);
            double fl=MathUtil.bilerp(flow[a],flow[b],flow[c],flow[d],tx,tz);
            int nearestX=tx<0.5?ix:ix+1;
            int nearestZ=tz<0.5?iz:iz+1;
            long basinId=basin[nearestZ*n+nearestX];

            int bestId=-1;
            Projection best=null;
            int idA=nearestSegment[a];
            int idB=nearestSegment[b];
            int idC=nearestSegment[c];
            int idD=nearestSegment[d];

            if(idA>=0) {
                bestId=idA;
                best=segments[idA].project(x,z);
            }
            if(idB>=0&&idB!=idA) {
                Projection p=segments[idB].project(x,z);
                if(best==null||p.distance<best.distance){best=p;bestId=idB;}
            }
            if(idC>=0&&idC!=idA&&idC!=idB) {
                Projection p=segments[idC].project(x,z);
                if(best==null||p.distance<best.distance){best=p;bestId=idC;}
            }
            if(idD>=0&&idD!=idA&&idD!=idB&&idD!=idC) {
                Projection p=segments[idD].project(x,z);
                if(best==null||p.distance<best.distance){best=p;bestId=idD;}
            }

            if(bestId<0) {
                return new HydroSample(eh,fh,Math.max(0.0,fh-eh),fl,basinId,
                        Double.POSITIVE_INFINITY,0,0,0,Double.NEGATIVE_INFINITY,0,0,0,Double.POSITIVE_INFINITY);
            }

            Segment s=segments[bestId];
            double t=best.t;
            double q=MathUtil.lerp(s.q1,s.q2,t);
            double ce=MathUtil.lerp(s.e1,s.e2,t);
            double cp=Math.pow(MathUtil.smoothstep(1.0,Math.max(8.0,Math.max(s.q1,s.q2)),q),0.9);
            double water=MathUtil.lerp(s.w1,s.w2,t);
            double grade=Math.abs(s.w2-s.w1)/s.length;
            Point tangent=s.tangent(t);
            double tangentLen=Math.max(1.0e-6,Math.hypot(tangent.x,tangent.z));
            double dirX=tangent.x/tangentLen,dirZ=tangent.z/tangentLen;
            return new HydroSample(eh,fh,Math.max(0.0,fh-eh),fl,basinId,
                    best.distance,q,ce,cp,water,grade,dirX,dirZ,best.signedDistance);
        }
    }
}
