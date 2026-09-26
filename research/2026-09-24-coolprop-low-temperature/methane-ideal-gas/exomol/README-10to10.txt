The 10to10 line list for 12CH4

===============================================================================
ExoMol line lists IV: The rotation-vibration spectrum of methane up to 1500 K
S. N. Yurchenko and J. Tennyson
MRNAS 2014


A hot line list is presented for 12CH4 in its ground  electronic 
state. This line list, called 10to10, contains 9.8 billion transitions and 
should be complete for temperatures up to 1500 K. It covers the wavelengths 
longer than 1 um and includes all transitions to upper states with 
energies below hc 18000 cm-1 and rotational excitation up to J=50.  The 
line list is computed using the eigenvalues and eigenfunctions of CH4 
obtained by variational solution of the Schroedinger equation for the 
rotation-vibration motion of nuclei employing program TROVE and a new 
'spectroscopic' potential energy surface (PES) obtained by refining an ab 
initio PES (CCSD(T)-F12c/aug-cc-pVQZ) in a least-squares fitting to the 
experimentally derived energies with J = 0, 1, 2, 3, 4 as extracted from the 
HITRAN database. The dipole transition probabilities are represented by the 
Einstein-A coefficients obtained using a previously reported ab initio dipole 
moment surface (CCSD(T)-F12c/aug-cc-pVTZ). Detailed comparisons with other 
available sources of methane transitions including HITRAN, experimental 
compilations and other theoretical line lists show that these sources lack 
transitions both higher temperatures and near infrared wavelengths.  The 
10to10 line list is suitable for modelling atmospheres of cool stars and 
exoplanets. 


Description: 

The data are in two parts. The first, 12C-1H4__YT10to10.states contains a list of  
rovibrational states. Each state is labelled with: nine normal mode 
vibrational quantum numbers and the vibrational symmety; three rotational 
quantum numbers including the total angular momentum J and rotational  
symmetry; the total symmetry quantum number Gamma and the running number 
in the same J,Gamma block. In addition there are nine local mode vibrational 
numbers and the largest coeffecient used to assign the state in question. 
Each rovibrational state has a unique number, which is the number of the 
row in which it appears in the file. This number is the means by 
which the state is related to the second part of the data system, 
the transitions files. The total degeneracy is also given to 
facilitate the intensity calculations.  

Because of their size, the transitions are listed in 120 separate
files, each containing all the transitions in a 100cm-1 frequency
range. These and their contents are ordered by increasing frequency.
The name of the file includes the lowest frequency in the range; thus
the 12C-1H4__YT10to10__00500.trans file contains all the transitions in the frequency
range 500-600cm-1.

The transition files 12C-1H4__YT10to10__xxxxx.trans contain three columns: 
the reference number in the energy file of the upper state; that of the lower state; 
and the Einstein A coefficient of the transition. The energy file and the
transitions files are zipped, and need to be extracted before use.

There is a Fortran 90 programme, s_10to10.f90 which may be used to generate 
synthetic spectra (see s_10to10.txt for details). Using this, it is possible 
to generate absorption or emission spectra in either 'stick' form or else 
cross-sections convoluted with a gaussian with the half-width at half maximum 
being specified by the user, or with a the temperature-dependent doppler 
half-width. Five saple input files for use with s_10to10.f90 are supplied.  

There is also a Fortran 90 program ch4.f90 to compute the Cartesian dipole 
moment components (D) and potential energy values (cm-1) of CH4 for an arbitrary 
geometry from the dipole moment and potential parameters. The program requires 
a Lapack subroutine dgells to solve a 3x3 system of linear equation, 
which can be replaced by any linear solver. 

ch4_par_10to10.inp is an input file for ch4.f90  containing the refined potential 
and ab initio dipole moment parameters of CH4 in a compact representation 
(only non-zero parameters are listed). 

File Summary:
-------------------------------------------------------------------------------
 FileName          Explanations
-------------------------------------------------------------------------------
ReadMe.dat                            This file
s_10to10.f90                          programme for generating spectra
s_sti750.inp                          illustration of 'stick' input file
s_dop296.inp                          illustration of 'doppl' input file
gauss300.inp                          illustration of 'gauss' input file
s_bin750.inp                          illustration of 'bin' input file 
s_pfu296.inp                          illustration of 'partfunc' input file 
s_10to10.txt                          explation of input structure for s_10to10.f90
ch4.f90                               a Fortran 90 program to compute DMS and PES of CH4
ch4_par_10to10.inp                    an input file for ch4.f90 
12C-1H4__YT10to10.states              labelled rovibrational states
12C-1H4__YT10to10__xxxxx.trans        121 Transition files (Einstein coefficients, 1/s) 
                                      divided into 100 cm-1 frequency pieces. 
                                      The transitions are sorted according with 
                                      wavenumber. xxxxx is the lower wavenumber bound. 
                                      See below for the description of columns.

-------------------------------------------------------------------------------

Byte-by-byte description of file: 12C-1H4__YT10to10__xxxxx.trans
-------------------------------------------------------------------------------
  Bytes Format Units   Label  Explanations
-------------------------------------------------------------------------------
  1- 12 i12     ---     i'    Upper state ID
 14- 25 i12     ---     i"    Lower state ID
 27- 36 e10.4   s-1     A     Einstein A-coefficient of the transition
-------------------------------------------------------------------------------

Byte-by-byte description of files: 12C-1H4__YT10to10.states 
-------------------------------------------------------------------------------
  Bytes Format Units   Label  Explanations
-------------------------------------------------------------------------------
   1- 12  i12   ---   i      State ID, non-negative integer index, starting at 1 
  14- 25  i12   cm-1  E      State energy term value in cm-1
  27- 32  i6    ---   g      Total state degeneracy 
  34- 40  i7    ---   J      [0/39] J-quantum number J$ is the total angular 
                                    momentum excluding nuclear spin
  41- 45  i5    ---   G      [1/5] Total symmetry in T_d_(M), 
                                   Gamma = A_1_,A_2_,E,F_1_,F_2_
  46- 51  i6    ---   n1     [0/10] A_1_-symmetry normal mode quantum number
  52- 55  i4    ---   n2     [0/20] E-symmetry normal mode quantum number 
  56- 59  i4    ---   L2     [0/20] L_2_ vib. angular momentum quantum number
  60- 63  i4    ---   n3     [0/10] F_1_-symmetry normal mode quantum number
  64- 67  i4    ---   L3     [0/10] L_3_ vib. angular momentum quantum number
  68- 71  i4    ---   M3     [0/10] M_3_ Multiplicity index quantum number
  72- 75  i4    ---   n4     [0/20] F_2_-symmetry normal mode quantum number
  76- 79  i4    ---   L4     [0/20] L_4_ vib. angular momentum quantum number
  80- 83  i4    ---   M4     [0/20] M_4_ Multiplicity index quantum number
  85- 88  i4    ---   Gv     [1/5] T_d_(M) vi. symmetry Gamma(v) (local mode)
  90- 95  i6    ---   Ja     [0/39] Total angular momentum quantum number,
                                    the same as J at 34-40
  96- 99  i4    ---   K      [0/39] Projection of J on axis of molec. symmetry 
 100-103  i4    ---   Pr     [0/1] Rotational parity tau(rot)
 104-107  i4    ---   Gr     [1/5] T_d_(M) rot. symmetry Gamma(v) (local mode)
 108-117  i10   ---   N(Bl)  [1/97054] Reference number in the polyad
 118-124  f7.2  ---   C2     [0.0/1.0000] Square of the largest coefficient 
 128-131  i4    ---   v1     [0/10] Local mode vibrational quantum number
 132-135  i4    ---   v2     [0/10] Local mode vibrational quantum number
 136-139  i4    ---   v3     [0/10] Local mode vibrational quantum number
 140-143  i4    ---   v4     [0/10] Local mode vibrational quantum number 
 144-147  i4    ---   v5     [0/20] Local mode vibrational quantum number
 148-151  i4    ---   v6     [0/20] Local mode vibrational quantum number
 152-155  i4    ---   v7     [0/20] Local mode vibrational quantum number
 156-159  i4    ---   v8     [0/20] Local mode vibrational quantum number
 160-163  i4    ---   v9     [0/20] Local mode vibrational quantum number
-------------------------------------------------------------------------------


Contacts:
   S.N. Yurchenko, s.yurchenko@ucl.ac.uk
   J. Tennyson,  j.tennyson@ucl.ac.uk
===============================================================================

